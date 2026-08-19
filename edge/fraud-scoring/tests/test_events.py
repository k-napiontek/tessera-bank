"""Parsing what arrives, and rendering what leaves."""

import json
from datetime import UTC, datetime

import pytest

from fraud_scoring.events import FraudDecision, MalformedEvent, TransferPosted, Verdict

from .fixtures import transfer_payload


def test_a_posted_transfer_is_parsed() -> None:
    event = TransferPosted.from_json(json.dumps(transfer_payload()))

    assert event.transfer_ref == "TB202608190000000001"
    assert event.amount.amount_minor == 250_000
    assert event.amount.currency == "PLN"
    assert event.correlation_id == "8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e"
    assert len(event.movements) == 2
    assert {leg.direction for leg in event.movements} == {"DEBIT", "CREDIT"}
    assert event.reverses_transfer_ref is None


def test_a_reversal_carries_the_transfer_it_reverses() -> None:
    event = TransferPosted.from_json(
        json.dumps(transfer_payload(reversesTransferRef="TB202608190000000002"))
    )

    assert event.reverses_transfer_ref == "TB202608190000000002"


def test_an_unknown_field_is_ignored_rather_than_refused() -> None:
    # Strict about what is published, lenient about what is accepted. Halting the whole estate's
    # scoring because a producer added a field is a worse incident than the one it prevents.
    event = TransferPosted.from_json(json.dumps(transfer_payload(somethingNew="whatever")))

    assert event.transfer_ref == "TB202608190000000001"


@pytest.mark.parametrize(
    "missing",
    ["transferRef", "debitAccountRef", "creditAccountRef", "amount", "postedAt", "correlationId"],
)
def test_a_missing_required_field_is_refused(missing: str) -> None:
    payload = transfer_payload()
    del payload[missing]

    with pytest.raises(MalformedEvent) as raised:
        TransferPosted.from_json(json.dumps(payload))

    assert missing in str(raised.value)


def test_a_message_that_is_not_json_is_refused() -> None:
    with pytest.raises(MalformedEvent):
        TransferPosted.from_json(b"\x00\x01 not json")


def test_a_transfer_without_exactly_two_legs_is_refused() -> None:
    payload = transfer_payload()
    payload["movements"] = payload["movements"][:1]

    with pytest.raises(MalformedEvent):
        TransferPosted.from_json(json.dumps(payload))


def test_an_amount_that_is_not_a_whole_number_is_refused() -> None:
    payload = transfer_payload()
    payload["amount"]["amountMinor"] = 2500.75

    # Money in this estate is minor units. A decimal here is a producer that has started treating
    # money as a real number, and scoring it would launder that mistake onward.
    with pytest.raises(MalformedEvent):
        TransferPosted.from_json(json.dumps(payload))


def test_a_boolean_amount_is_refused() -> None:
    payload = transfer_payload()
    payload["amount"]["amountMinor"] = True

    # bool is an int in Python, so True would otherwise be scored as one minor unit.
    with pytest.raises(MalformedEvent):
        TransferPosted.from_json(json.dumps(payload))


def test_a_timestamp_without_a_zone_is_refused() -> None:
    payload = transfer_payload(postedAt="2026-08-19T13:19:21.451")

    # The out-of-hours rule reads this field. A naive timestamp would be scored in whatever zone
    # the host happens to run in, which makes the same event score differently in two data centres.
    with pytest.raises(MalformedEvent):
        TransferPosted.from_json(json.dumps(payload))


def test_timestamps_are_normalised_to_utc() -> None:
    event = TransferPosted.from_json(
        json.dumps(transfer_payload(postedAt="2026-08-19T15:19:21+02:00"))
    )

    assert event.posted_at == datetime(2026, 8, 19, 13, 19, 21, tzinfo=UTC)


def test_a_decision_renders_the_contract_shape() -> None:
    decision = FraudDecision(
        transfer_ref="TB202608190000000001",
        correlation_id="8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e",
        verdict=Verdict(
            decision="REVIEW",
            score=420,
            reason_codes=("AMT_HIGH",),
            model_version="rules-2026.08.1",
        ),
        decided_at=datetime(2026, 8, 19, 13, 20, 0, tzinfo=UTC),
    )

    payload = decision.to_payload()

    assert payload == {
        "transferRef": "TB202608190000000001",
        "decision": "REVIEW",
        "score": 420,
        "reasonCodes": ["AMT_HIGH"],
        "modelVersion": "rules-2026.08.1",
        "decidedAt": "2026-08-19T13:20:00.000Z",
        "correlationId": "8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e",
    }


def test_the_same_verdict_always_serialises_to_the_same_bytes() -> None:
    verdict = Verdict(
        decision="ALLOW", score=10, reason_codes=("AMT_RND",), model_version="rules-2026.08.1"
    )
    moment = datetime(2026, 8, 19, 13, 20, 0, tzinfo=UTC)

    first = FraudDecision(
        "TB202608190000000001", "8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e", verdict, moment
    )
    second = FraudDecision(
        "TB202608190000000001", "8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e", verdict, moment
    )

    # Sorted keys, fixed separators. Two runs producing different bytes for the same conclusion
    # would make every downstream comparison a judgement call.
    assert first.to_json() == second.to_json()


def test_a_verdict_is_frozen() -> None:
    verdict = Verdict("ALLOW", 0, (), "rules-2026.08.1")

    with pytest.raises(AttributeError):
        verdict.score = 999  # type: ignore[misc]
