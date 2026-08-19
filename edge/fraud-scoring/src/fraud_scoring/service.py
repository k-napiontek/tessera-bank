"""The loop: take one event, score it, publish the decision, then commit the offset.

**That order is the guarantee.** Committing first and publishing afterwards means a crash between
the two loses a decision permanently, and nothing anywhere records that it happened - the offset has
moved, so the event is never redelivered. Publishing first means a crash produces the event a second
time, which is a duplicate rather than a loss. This is the same argument the ledger's outbox relay
makes about marking a row dispatched, and it comes out the same way: at-least-once, deliberately.

A duplicate is safe here because scoring is a pure function. The same transfer scores identically
however many times it arrives, the decision topic is keyed by ``transferRef``, and a compacted view
therefore holds one decision per transfer. What this service promises is **exactly one distinct
decision**, not exactly one message.

A message that cannot be parsed is counted, logged and skipped. The alternative - stopping - means
one malformed message on the topic ends all scoring for the bank, on every restart, for ever.
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Protocol

from fraud_scoring.events import FraudDecision, MalformedEvent, TransferPosted
from fraud_scoring.scoring import Engine

LOG = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class Envelope:
    """One message as it arrived, with whatever the source needs to commit it."""

    value: bytes
    offset: object = None


class EventSource(Protocol):
    """Where transfer events come from. Kafka in production, a list in a test."""

    def poll(self, timeout_seconds: float) -> Envelope | None: ...

    def commit(self, envelope: Envelope) -> None: ...

    def close(self) -> None: ...


class DecisionSink(Protocol):
    """Where decisions go. Publishing returns only once the broker has accepted the message."""

    def publish(self, key: str, payload: bytes) -> None: ...

    def close(self) -> None: ...


class Observer(Protocol):
    """Counts what happened. The metrics implementation satisfies it; so does a no-op in a test."""

    def scored(self, decision: str, score: int, seconds: float) -> None: ...

    def malformed(self) -> None: ...

    def publish_failed(self) -> None: ...


class NullObserver:
    """Records nothing, so a test does not have to care."""

    def scored(self, decision: str, score: int, seconds: float) -> None: ...

    def malformed(self) -> None: ...

    def publish_failed(self) -> None: ...


class ScoringService:
    """Consumes transfers, publishes decisions."""

    def __init__(
        self,
        source: EventSource,
        sink: DecisionSink,
        engine: Engine,
        *,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
        observer: Observer | None = None,
        poll_seconds: float = 1.0,
    ) -> None:
        self._source = source
        self._sink = sink
        self._engine = engine
        self._clock = clock
        self._observer = observer or NullObserver()
        self._poll_seconds = poll_seconds

    def run_once(self) -> bool:
        """Handle at most one message. Returns False when there was nothing to handle."""
        envelope = self._source.poll(self._poll_seconds)
        if envelope is None:
            return False

        try:
            event = TransferPosted.from_json(envelope.value)
        except MalformedEvent as broken:
            # Committed deliberately. Leaving the offset where it is would make this message the
            # only thing this service ever reads again.
            LOG.warning(
                "skipping a message that is not a transfer event",
                extra={"reason": str(broken)},
            )
            self._observer.malformed()
            self._source.commit(envelope)
            return True

        started = self._clock()
        verdict = self._engine.score(event)
        decided_at = self._clock()

        decision = FraudDecision(
            transfer_ref=event.transfer_ref,
            correlation_id=event.correlation_id,
            verdict=verdict,
            decided_at=decided_at,
        )

        # Publish first. If this raises, the offset is not committed and the event comes back.
        self._sink.publish(key=event.transfer_ref, payload=decision.to_json())
        self._source.commit(envelope)

        LOG.info(
            "transfer scored",
            extra={
                "transfer_ref": event.transfer_ref,
                "correlation_id": event.correlation_id,
                "decision": verdict.decision,
                "score": verdict.score,
                "reason_codes": ",".join(verdict.reason_codes),
                "model_version": verdict.model_version,
            },
        )
        self._observer.scored(
            verdict.decision, verdict.score, (decided_at - started).total_seconds()
        )
        return True

    def run_until(self, keep_going: Callable[[], bool]) -> None:
        """Run the loop while ``keep_going()`` is true.

        A publish that fails is retried by redelivery rather than by looping here: the offset was
        not committed, so the same event comes back on the next poll. Every failure is logged with
        its traceback and counted, so a service that is failing every message looks different from
        one with nothing to do - which is the distinction a swallowed exception destroys.
        """
        while keep_going():
            try:
                self.run_once()
            except Exception:
                # Broad on purpose: the loop must survive a broker hiccup. It is not silent - the
                # failure is logged with its traceback and counted, and the offset stays where it
                # was, so nothing is lost by continuing.
                LOG.exception("failed to handle a message; it will be redelivered")
                self._observer.publish_failed()

    def close(self) -> None:
        self._source.close()
        self._sink.close()
