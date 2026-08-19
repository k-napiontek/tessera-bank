"""The loop, against fakes: what is published, in what order, and what happens when it fails."""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest

from fraud_scoring import rules
from fraud_scoring.scoring import Engine, Parameters
from fraud_scoring.service import Envelope, ScoringService

from .fixtures import transfer_payload

PARAMETERS = Parameters(
    high_amount_minor=10_000_000,
    reporting_threshold_minor=1_000_000,
    review_threshold=400,
    block_threshold=750,
)


class FakeSource:
    """A queue of messages, and a record of what was committed and when."""

    def __init__(self, *messages: bytes) -> None:
        self.pending = [Envelope(value=value, offset=i) for i, value in enumerate(messages)]
        self.committed: list[object] = []
        self.closed = False
        self.journal: list[str] = []

    def poll(self, timeout_seconds: float) -> Envelope | None:
        return self.pending.pop(0) if self.pending else None

    def commit(self, envelope: Envelope) -> None:
        self.journal.append(f"commit:{envelope.offset}")
        self.committed.append(envelope.offset)

    def close(self) -> None:
        self.closed = True


class FakeSink:
    """Records what was published, and can be told to fail."""

    def __init__(self, fail_times: int = 0, journal: list[str] | None = None) -> None:
        self.published: list[tuple[str, dict[str, Any]]] = []
        self.fail_times = fail_times
        self.closed = False
        self.journal = journal if journal is not None else []

    def publish(self, key: str, payload: bytes) -> None:
        if self.fail_times > 0:
            self.fail_times -= 1
            raise RuntimeError("the broker refused the decision")
        self.journal.append(f"publish:{key}")
        self.published.append((key, json.loads(payload)))

    def close(self) -> None:
        self.closed = True


class CountingObserver:
    def __init__(self) -> None:
        self.scores: list[tuple[str, int, float]] = []
        self.malformed_count = 0
        self.publish_failures = 0

    def scored(self, decision: str, score: int, seconds: float) -> None:
        self.scores.append((decision, score, seconds))

    def malformed(self) -> None:
        self.malformed_count += 1

    def publish_failed(self) -> None:
        self.publish_failures += 1


def message(**overrides: Any) -> bytes:
    return json.dumps(transfer_payload(**overrides)).encode()


def service(source: FakeSource, sink: FakeSink, **kwargs: Any) -> ScoringService:
    return ScoringService(source, sink, Engine(rules.RULE_SET, PARAMETERS), **kwargs)


def test_one_event_produces_one_decision() -> None:
    source, sink = FakeSource(message()), FakeSink()

    assert service(source, sink).run_once() is True

    assert len(sink.published) == 1
    key, payload = sink.published[0]
    assert key == "TB202608190000000001"
    assert payload["transferRef"] == "TB202608190000000001"
    assert payload["decision"] == "ALLOW"
    assert payload["correlationId"] == "8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e"


def test_the_decision_is_keyed_by_the_transfer() -> None:
    source, sink = FakeSource(message()), FakeSink()

    service(source, sink).run_once()

    # Keying by transferRef is what makes a duplicate collapse under compaction, and what makes the
    # decision co-partition with the transfer it describes.
    assert sink.published[0][0] == "TB202608190000000001"


def test_the_offset_is_committed_only_after_the_decision_is_published() -> None:
    journal: list[str] = []
    source, sink = FakeSource(message()), FakeSink(journal=journal)
    source.journal = journal

    service(source, sink).run_once()

    # The whole ordering guarantee, in one assertion. Reversed, a crash between the two loses a
    # decision permanently and leaves nothing behind to say so.
    assert journal == ["publish:TB202608190000000001", "commit:0"]


def test_a_failed_publish_leaves_the_offset_alone() -> None:
    source, sink = FakeSource(message()), FakeSink(fail_times=1)

    with pytest.raises(RuntimeError):
        service(source, sink).run_once()

    # Not committed, so the event is redelivered and scored again rather than lost.
    assert source.committed == []


def test_a_redelivered_event_scores_identically() -> None:
    payload = message()
    source, sink = FakeSource(payload, payload), FakeSink()
    scorer = service(source, sink)

    scorer.run_once()
    scorer.run_once()

    first, second = sink.published
    # Everything except the timestamp: when scoring happened is a fact about the run, not about the
    # transfer. This is what "exactly one distinct decision" means in practice.
    assert {k: v for k, v in first[1].items() if k != "decidedAt"} == {
        k: v for k, v in second[1].items() if k != "decidedAt"
    }


def test_a_malformed_message_is_skipped_rather_than_stopping_the_service() -> None:
    observer = CountingObserver()
    source, sink = FakeSource(b"{ this is not json", message()), FakeSink()
    scorer = service(source, sink, observer=observer)

    scorer.run_once()
    scorer.run_once()

    # One malformed message must not end scoring for the whole bank on every restart, for ever.
    assert observer.malformed_count == 1
    assert source.committed == [0, 1]
    assert len(sink.published) == 1


def test_an_empty_poll_reports_that_there_was_nothing_to_do() -> None:
    assert service(FakeSource(), FakeSink()).run_once() is False


def test_the_loop_survives_a_broker_that_refuses_once() -> None:
    observer = CountingObserver()
    payload = message()
    source, sink = FakeSource(payload, payload), FakeSink(fail_times=1)
    scorer = service(source, sink, observer=observer)

    calls = iter([True, True, False])
    scorer.run_until(lambda: next(calls))

    # The first attempt failed and was counted; the redelivery succeeded. A loop that died here
    # would turn one broker hiccup into an outage that needs an operator.
    assert observer.publish_failures == 1
    assert len(sink.published) == 1


def test_what_is_observed_is_the_decision_and_how_long_it_took() -> None:
    observer = CountingObserver()
    ticks = iter(
        [
            datetime(2026, 8, 19, 13, 0, 0, tzinfo=UTC),
            datetime(2026, 8, 19, 13, 0, 0, tzinfo=UTC) + timedelta(milliseconds=25),
        ]
    )
    source, sink = FakeSource(message()), FakeSink()

    service(source, sink, observer=observer, clock=lambda: next(ticks)).run_once()

    assert observer.scores == [("ALLOW", 0, 0.025)]


def test_the_decision_records_when_it_was_taken() -> None:
    moment = datetime(2026, 8, 19, 13, 30, 0, tzinfo=UTC)
    source, sink = FakeSource(message()), FakeSink()

    service(source, sink, clock=lambda: moment).run_once()

    assert sink.published[0][1]["decidedAt"] == "2026-08-19T13:30:00.000Z"


def test_an_impossible_transfer_is_reviewed_rather_than_blocked() -> None:
    source, sink = FakeSource(message(creditAccountRef="TB00000000000C03")), FakeSink()

    service(source, sink).run_once()

    # SELFPAY weighs 500 against a block threshold of 750, so this reaches REVIEW and not BLOCK -
    # and that is the right answer rather than a gap in the weights. The ledger cannot produce a
    # transfer to and from one account, so what is suspect here is the *event*, not necessarily the
    # money. BLOCK triggers a reversal of a posting that may be perfectly good; REVIEW puts it in
    # front of somebody who can tell the difference.
    payload = sink.published[0][1]
    assert payload["decision"] == "REVIEW"
    assert "SELFPAY" in payload["reasonCodes"]


def test_a_blocking_score_is_published_like_any_other_decision() -> None:
    # A large, round, out-of-hours transfer: 300 + 100 + 100, plus SELFPAY's 500, is over 750.
    source, sink = (
        FakeSource(
            message(
                creditAccountRef="TB00000000000C03",
                amount={"amountMinor": 10_000_000, "currency": "PLN"},
                postedAt="2026-08-19T02:00:00Z",
            )
        ),
        FakeSink(),
    )

    service(source, sink).run_once()

    # BLOCK is a decision, not an action: this service publishes it and reverses nothing. The
    # ledger's own reversal path does that, with its own audit trail.
    payload = sink.published[0][1]
    assert payload["decision"] == "BLOCK"
    assert source.committed == [0]


def test_closing_the_service_closes_both_ends() -> None:
    source, sink = FakeSource(), FakeSink()

    service(source, sink).close()

    assert source.closed and sink.closed
