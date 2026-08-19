"""The engine, exercised with rules that exist only for these tests.

The real catalogue is tested separately. Mixing the two would mean a change to a rule's weight broke
the tests for the engine that applies it.
"""

from __future__ import annotations

import json

import pytest

from fraud_scoring.events import TransferPosted
from fraud_scoring.scoring import MAX_SCORE, Engine, Parameters, Rule, RuleSet, adjusted

from .fixtures import transfer_payload


def event(**overrides: object) -> TransferPosted:
    return TransferPosted.from_json(json.dumps(transfer_payload(**overrides)))


def parameters() -> Parameters:
    return Parameters(
        high_amount_minor=10_000_000,
        reporting_threshold_minor=1_000_000,
        review_threshold=400,
        block_threshold=750,
    )


def always(code: str, weight: int) -> Rule:
    return Rule(code=code, weight=weight, description=code, fires=lambda event, params: True)


def never(code: str, weight: int) -> Rule:
    return Rule(code=code, weight=weight, description=code, fires=lambda event, params: False)


def engine(*rules: Rule, version: str = "test-1") -> Engine:
    return Engine(RuleSet(version=version, rules=rules), parameters())


def test_a_transfer_that_fires_nothing_is_allowed() -> None:
    verdict = engine(never("NOPE", 900)).score(event())

    assert verdict.decision == "ALLOW"
    assert verdict.score == 0
    assert verdict.reason_codes == ()


def test_the_score_is_the_sum_of_what_fired() -> None:
    verdict = engine(always("A", 100), never("B", 500), always("C", 250)).score(event())

    assert verdict.score == 350
    assert verdict.reason_codes == ("A", "C")


def test_reason_codes_follow_catalogue_order() -> None:
    verdict = engine(always("Z", 10), always("A", 10), always("M", 10)).score(event())

    # Catalogue order, not alphabetical and not order of firing. Two runs listing the same reasons
    # differently would make every comparison between decisions a judgement call.
    assert verdict.reason_codes == ("Z", "A", "M")


@pytest.mark.parametrize(
    ("weight", "expected"),
    [
        (0, "ALLOW"),
        (399, "ALLOW"),
        (400, "REVIEW"),
        (749, "REVIEW"),
        (750, "BLOCK"),
        (999, "BLOCK"),
    ],
)
def test_the_thresholds_are_inclusive_lower_bounds(weight: int, expected: str) -> None:
    verdict = engine(always("W", weight)).score(event())

    # A threshold of 400 means 400 is reviewed. Off by one here is a transfer that should have been
    # stopped and was not, so the boundary is pinned rather than left to the reader.
    assert verdict.decision == expected


def test_the_score_is_clamped_to_the_contracts_range() -> None:
    verdict = engine(always("A", 900), always("B", 900)).score(event())

    # The contract fixes the range at 0-1000. Left unclamped, this decision would be refused on the
    # wire after all the work had been done.
    assert verdict.score == MAX_SCORE
    assert verdict.decision == "BLOCK"


def test_scoring_the_same_event_twice_gives_the_same_verdict() -> None:
    scorer = engine(always("A", 100), never("B", 100), always("C", 300))
    subject = event()

    first = scorer.score(subject)
    second = scorer.score(subject)

    # REQ-FRD-003. This is the property the whole design of the engine exists to protect.
    assert first == second


def test_two_engines_over_the_same_catalogue_agree() -> None:
    subject = event()
    rules = (always("A", 100), always("C", 300))

    # A restarted process, or a second instance, must reach the same conclusion. An engine that
    # accumulated anything between calls would fail here.
    assert engine(*rules).score(subject) == engine(*rules).score(subject)


def test_the_model_version_names_the_catalogue_and_the_parameters() -> None:
    scorer = engine(always("A", 100), version="rules-2026.08.1")

    assert scorer.model_version.startswith("rules-2026.08.1+")
    # The contract caps modelVersion at 32 characters, and a version that does not fit is a version
    # that gets truncated somewhere downstream.
    assert len(scorer.model_version) <= 32


def test_changing_a_parameter_changes_the_recorded_version() -> None:
    scorer = engine(always("A", 100))
    tightened = scorer.with_parameters(adjusted(scorer.parameters, high_amount_minor=5_000_000))

    # Without this, a decision could be reproduced from its recorded version only if nobody had
    # touched the configuration since - which is precisely the assumption model risk management
    # exists to forbid.
    assert scorer.model_version != tightened.model_version


def test_the_same_parameters_always_fingerprint_the_same_way() -> None:
    assert Parameters(1, 2, 3, 4).fingerprint() == Parameters(1, 2, 3, 4).fingerprint()


def test_different_parameters_do_not_share_a_fingerprint() -> None:
    # The values are chosen to collide under naive concatenation: "1" + "23" and "12" + "3" are the
    # same string, and a fingerprint two different setups share certifies the wrong one.
    assert Parameters(1, 23, 4, 5).fingerprint() != Parameters(12, 3, 4, 5).fingerprint()


def test_a_rule_sees_the_parameters_in_force() -> None:
    seen: list[Parameters] = []

    def record(event: TransferPosted, params: Parameters) -> bool:
        seen.append(params)
        return False

    engine(Rule("REC", 0, "records", record)).score(event())

    assert seen == [parameters()]
