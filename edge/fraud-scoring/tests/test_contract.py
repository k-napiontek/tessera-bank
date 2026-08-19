"""The contract, not the code, decides what these messages look like.

``contracts/asyncapi/ledger-events.yaml`` is the source of truth for both topics. These tests read
that document and validate against it, so a change to the payloads here fails unless the contract
changed first - which is the whole point of having one.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest
import yaml
from jsonschema import Draft202012Validator, ValidationError

from fraud_scoring.config import MAX_SCORE, MIN_SCORE
from fraud_scoring.events import FraudDecision, TransferPosted, Verdict

from .fixtures import transfer_payload

CONTRACT = Path(__file__).resolve().parents[3] / "contracts" / "asyncapi" / "ledger-events.yaml"


def document() -> dict[str, Any]:
    assert CONTRACT.is_file(), f"the contract must be readable from the tests: {CONTRACT}"
    return yaml.safe_load(CONTRACT.read_text(encoding="utf-8"))


def validator(schema_name: str) -> Draft202012Validator:
    """A validator for one schema, resolving the document's internal references.

    The whole AsyncAPI document is handed over as the schema root and the entry point is a $ref
    into it. Every reference in these payloads is internal - ``#/components/schemas/Money`` and the
    like - so giving the resolver the whole document is what makes them resolve, and it avoids
    copying a subset that would then be a second source of truth.
    """
    return Draft202012Validator({"$ref": f"#/components/schemas/{schema_name}", **document()})


def test_the_topics_this_service_uses_are_the_ones_the_contract_declares() -> None:
    from fraud_scoring.config import Settings

    channels = document()["channels"]

    assert Settings.DEFAULTS["TB_FRAUD_TRANSFER_TOPIC"] == channels["transferPosted"]["address"]
    assert Settings.DEFAULTS["TB_FRAUD_DECISION_TOPIC"] == channels["fraudDecision"]["address"]


def test_the_fixture_is_a_valid_transfer_posted_event() -> None:
    # If this fails, every other test in the suite has been scoring something the ledger would
    # never publish - which is the quiet way a consumer ends up tested against fiction.
    validator("TransferPostedPayload").validate(transfer_payload())


def test_a_published_decision_validates_against_the_contract() -> None:
    decision = FraudDecision(
        transfer_ref="TB202608190000000001",
        correlation_id="8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e",
        verdict=Verdict(
            decision="BLOCK",
            score=880,
            reason_codes=("AMT_HIGH", "AMT_RND"),
            model_version="rules-2026.08.1",
        ),
        decided_at=datetime(2026, 8, 19, 13, 20, 0, tzinfo=UTC),
    )

    validator("FraudDecisionPayload").validate(json.loads(decision.to_json()))


@pytest.mark.parametrize("verdict", ["ALLOW", "REVIEW", "BLOCK"])
def test_every_outcome_this_service_can_reach_is_one_the_contract_allows(verdict: str) -> None:
    decision = FraudDecision(
        transfer_ref="TB202608190000000001",
        correlation_id="8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e",
        verdict=Verdict(verdict, 0, (), "rules-2026.08.1"),
        decided_at=datetime(2026, 8, 19, 13, 20, 0, tzinfo=UTC),
    )

    validator("FraudDecisionPayload").validate(json.loads(decision.to_json()))


def test_the_score_range_is_taken_from_the_contract_rather_than_invented() -> None:
    schema = document()["components"]["schemas"]["FraudDecisionPayload"]["properties"]["score"]

    # The thresholds are validated against these two numbers at boot. Hard-coding a different range
    # here would let a configuration through that the contract rejects on the wire.
    assert schema["minimum"] == MIN_SCORE
    assert schema["maximum"] == MAX_SCORE
    assert schema["type"] == "integer"


def test_a_reason_code_longer_than_the_contract_allows_is_caught() -> None:
    limit = document()["components"]["schemas"]["FraudDecisionPayload"]["properties"][
        "reasonCodes"
    ]["items"]["maxLength"]
    decision = FraudDecision(
        transfer_ref="TB202608190000000001",
        correlation_id="8f14e45f-ce0a-4c1e-9f2b-3d4a5b6c7d8e",
        verdict=Verdict("REVIEW", 500, ("X" * (limit + 1),), "rules-2026.08.1"),
        decided_at=datetime(2026, 8, 19, 13, 20, 0, tzinfo=UTC),
    )

    # Reason codes are capped at 8 characters. The rule catalogue is checked against this limit in
    # its own test; this proves the contract would reject a code that slipped past it.
    with pytest.raises(ValidationError):
        validator("FraudDecisionPayload").validate(json.loads(decision.to_json()))


def test_the_parser_accepts_every_event_the_contract_permits() -> None:
    # A reversal is the shape most likely to be forgotten: the field is optional, and a consumer
    # that has never seen one will meet its first during an incident.
    payload = transfer_payload(reversesTransferRef="TB202608190000000002")
    validator("TransferPostedPayload").validate(payload)

    event = TransferPosted.from_json(json.dumps(payload))

    assert event.reverses_transfer_ref == "TB202608190000000002"
