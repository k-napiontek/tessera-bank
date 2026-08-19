"""The two messages this service speaks, and nothing else.

Both are defined in ``contracts/asyncapi/ledger-events.yaml``; a test validates every published
decision against that document, so these types cannot drift from it silently.

The service is **strict about what it publishes and lenient about what it accepts**. A decision that
does not match the contract is never sent. An incoming event carrying a field this version does not
know about is scored anyway: refusing it would stop the bank being scored because a producer added
something, and a consumer that halts the whole estate over a field it could have ignored has caused
a worse incident than the one it prevented.

Money is minor units and a currency code, never a float. That is not a style rule - it is why the
score is an integer too: a decision boundary computed in binary floating point is a boundary that
lands differently on a different runtime, and "reproducible" would then be false.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Final, Self

DEBIT: Final = "DEBIT"
CREDIT: Final = "CREDIT"

ALLOW: Final = "ALLOW"
REVIEW: Final = "REVIEW"
BLOCK: Final = "BLOCK"

# The score's range and the reason code's length, as the contract fixes them. They live beside the
# types that go on the wire so that the configuration and the engine cannot hold two opinions about
# what the contract allows.
MIN_SCORE: Final = 0
MAX_SCORE: Final = 1000
MAX_CODE_LENGTH: Final = 8


class MalformedEvent(Exception):
    """The bytes on the topic are not a transfer this service can score."""


@dataclass(frozen=True, slots=True)
class Money:
    amount_minor: int
    currency: str

    @classmethod
    def from_payload(cls, payload: Any, where: str) -> Self:
        obj = _object(payload, where)
        return cls(
            amount_minor=_integer(obj.get("amountMinor"), f"{where}.amountMinor"),
            currency=_text(obj.get("currency"), f"{where}.currency"),
        )


@dataclass(frozen=True, slots=True)
class Movement:
    movement_ref: str
    account_ref: str
    direction: str
    amount: Money

    @classmethod
    def from_payload(cls, payload: Any, where: str) -> Self:
        obj = _object(payload, where)
        return cls(
            movement_ref=_text(obj.get("movementRef"), f"{where}.movementRef"),
            account_ref=_text(obj.get("accountRef"), f"{where}.accountRef"),
            direction=_text(obj.get("direction"), f"{where}.direction"),
            amount=Money.from_payload(obj.get("amount"), f"{where}.amount"),
        )


@dataclass(frozen=True, slots=True)
class TransferPosted:
    """A balanced pair of postings that was committed to the ledger."""

    transfer_ref: str
    debit_account_ref: str
    credit_account_ref: str
    amount: Money
    posted_at: datetime
    correlation_id: str
    movements: tuple[Movement, ...]
    reverses_transfer_ref: str | None = None

    @classmethod
    def from_json(cls, raw: bytes | str) -> Self:
        try:
            payload = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError) as broken:
            # UnicodeDecodeError and not only JSONDecodeError: json.loads decodes bytes itself and
            # guesses the encoding from the first characters, so a message that is not UTF-8 at all
            # fails before parsing begins. Left uncaught, one such message on the topic takes the
            # consumer down on every restart, for ever.
            raise MalformedEvent(f"the message is not JSON: {broken}") from broken

        obj = _object(payload, "payload")
        movements = obj.get("movements")
        if not isinstance(movements, list) or len(movements) != 2:
            # The contract fixes this at exactly two, one debit and one credit. A payload with any
            # other number is not a transfer that posted, whatever else it is.
            raise MalformedEvent("payload.movements must hold exactly two legs")

        return cls(
            transfer_ref=_text(obj.get("transferRef"), "payload.transferRef"),
            debit_account_ref=_text(obj.get("debitAccountRef"), "payload.debitAccountRef"),
            credit_account_ref=_text(obj.get("creditAccountRef"), "payload.creditAccountRef"),
            amount=Money.from_payload(obj.get("amount"), "payload.amount"),
            posted_at=_instant(obj.get("postedAt"), "payload.postedAt"),
            correlation_id=_text(obj.get("correlationId"), "payload.correlationId"),
            movements=tuple(
                Movement.from_payload(leg, f"payload.movements[{i}]")
                for i, leg in enumerate(movements)
            ),
            reverses_transfer_ref=_optional_text(
                obj.get("reversesTransferRef"), "payload.reversesTransferRef"
            ),
        )


@dataclass(frozen=True, slots=True)
class Verdict:
    """What the rules concluded: the part of a decision that must be reproducible.

    ``decidedAt`` is deliberately **not** here. When scoring happened is a fact about this run, not
    about the transfer, and a replay genuinely happens at a different time. Freezing that timestamp
    to make two payloads byte-identical would be a service lying about when it did its work, which
    is a worse failure than two payloads differing in a field that is supposed to differ.
    """

    decision: str
    score: int
    reason_codes: tuple[str, ...]
    model_version: str


@dataclass(frozen=True, slots=True)
class FraudDecision:
    """A verdict about one transfer, ready to publish."""

    transfer_ref: str
    correlation_id: str
    verdict: Verdict
    decided_at: datetime

    def to_payload(self) -> dict[str, Any]:
        """The wire form, exactly as ``FraudDecisionPayload`` declares it."""
        return {
            "transferRef": self.transfer_ref,
            "decision": self.verdict.decision,
            "score": self.verdict.score,
            "reasonCodes": list(self.verdict.reason_codes),
            "modelVersion": self.verdict.model_version,
            "decidedAt": _iso(self.decided_at),
            "correlationId": self.correlation_id,
        }

    def to_json(self) -> bytes:
        # Sorted keys and no whitespace: the same verdict serialises to the same bytes on every
        # run, which is what makes the reproducibility claim checkable rather than asserted.
        return json.dumps(self.to_payload(), sort_keys=True, separators=(",", ":")).encode()


def _object(value: Any, where: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise MalformedEvent(f"{where} must be an object, got {type(value).__name__}")
    return value


def _text(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value:
        raise MalformedEvent(f"{where} must be a non-empty string")
    return value


def _optional_text(value: Any, where: str) -> str | None:
    if value is None:
        return None
    return _text(value, where)


def _integer(value: Any, where: str) -> int:
    # bool is an int in Python, and True would otherwise become an amount of one minor unit.
    if isinstance(value, bool) or not isinstance(value, int):
        raise MalformedEvent(f"{where} must be a whole number of minor units")
    return value


def _instant(value: Any, where: str) -> datetime:
    text = _text(value, where)
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as broken:
        raise MalformedEvent(f"{where} is not an ISO-8601 timestamp: {text!r}") from broken
    if parsed.tzinfo is None:
        # A timestamp with no zone is a timestamp nobody can place. The out-of-hours rule reads
        # this field, so a naive value would silently be scored in whatever zone the host runs in.
        raise MalformedEvent(f"{where} carries no time zone: {text!r}")
    return parsed.astimezone(UTC)


def _iso(moment: datetime) -> str:
    return moment.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
