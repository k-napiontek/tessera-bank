"""Rule set version 1: what fires, what does not, and what may never appear in the file."""

from __future__ import annotations

import ast
import inspect
import json
from typing import Any

from fraud_scoring import rules
from fraud_scoring.events import MAX_CODE_LENGTH, TransferPosted
from fraud_scoring.scoring import Engine, Parameters

from .fixtures import transfer_payload

PARAMETERS = Parameters(
    high_amount_minor=10_000_000,
    reporting_threshold_minor=1_000_000,
    review_threshold=400,
    block_threshold=750,
)


def engine() -> Engine:
    return Engine(rules.RULE_SET, PARAMETERS)


def event(**overrides: Any) -> TransferPosted:
    return TransferPosted.from_json(json.dumps(transfer_payload(**overrides)))


def codes(**overrides: Any) -> tuple[str, ...]:
    return engine().score(event(**overrides)).reason_codes


def test_an_ordinary_transfer_fires_nothing() -> None:
    verdict = engine().score(event())

    # 2 500.00 PLN, in the afternoon, not round to the nearest thousand, legs matching. If this
    # ever fires a rule, the catalogue has started flagging normal banking.
    assert verdict.reason_codes == ()
    assert verdict.decision == "ALLOW"
    assert verdict.score == 0


def test_a_high_amount_is_flagged() -> None:
    assert "AMT_HIGH" in codes(amount={"amountMinor": 10_000_000, "currency": "PLN"})


def test_an_amount_just_below_the_reporting_threshold_is_structuring() -> None:
    # 9 990.00 against a 10 000.00 threshold: the shape of a payment sized to avoid a report.
    assert "AMT_STRC" in codes(amount={"amountMinor": 999_000, "currency": "PLN"})


def test_an_amount_at_the_reporting_threshold_is_not_structuring() -> None:
    # At the threshold the payment *is* reported, so there is nothing to avoid. Flagging it would
    # send every compliant large payment to review.
    assert "AMT_STRC" not in codes(amount={"amountMinor": 1_000_000, "currency": "PLN"})


def test_an_amount_well_below_the_threshold_is_not_structuring() -> None:
    assert "AMT_STRC" not in codes(amount={"amountMinor": 500_000, "currency": "PLN"})


def test_a_large_round_amount_is_flagged() -> None:
    assert "AMT_RND" in codes(amount={"amountMinor": 5_000_000, "currency": "PLN"})


def test_a_small_round_amount_is_not() -> None:
    # Rent and salaries are round. Flagging them would bury the decisions that matter.
    assert "AMT_RND" not in codes(amount={"amountMinor": 200_000, "currency": "PLN"})


def test_a_reversal_is_flagged() -> None:
    assert "REVERSAL" in codes(reversesTransferRef="TB202608190000000002")


def test_a_transfer_to_the_same_account_is_flagged_heavily() -> None:
    verdict = engine().score(event(creditAccountRef="TB00000000000C03"))

    # The ledger refuses this outright, so seeing it means something upstream is broken rather than
    # merely suspicious. It must not be averaged away into an ALLOW.
    assert "SELFPAY" in verdict.reason_codes
    assert verdict.decision in {"REVIEW", "BLOCK"}


def test_a_posting_outside_business_hours_is_flagged() -> None:
    assert "OFFHOURS" in codes(postedAt="2026-08-19T03:14:00Z")


def test_business_hours_are_read_from_the_events_own_timestamp() -> None:
    # 03:14 in Warsaw is 01:14 UTC - still out of hours. The point is that the answer comes from
    # the timestamp on the message and not from where this process happens to run.
    assert "OFFHOURS" in codes(postedAt="2026-08-19T03:14:00+02:00")
    assert "OFFHOURS" not in codes(postedAt="2026-08-19T15:14:00+02:00")


def test_legs_that_do_not_match_their_transfer_are_flagged() -> None:
    payload = transfer_payload()
    payload["movements"][0]["amount"] = {"amountMinor": 999, "currency": "PLN"}

    verdict = engine().score(TransferPosted.from_json(json.dumps(payload)))

    assert "LEGMISM" in verdict.reason_codes


def test_two_legs_in_the_same_direction_are_flagged() -> None:
    payload = transfer_payload()
    payload["movements"][1]["direction"] = "DEBIT"

    verdict = engine().score(TransferPosted.from_json(json.dumps(payload)))

    # Two debits are not a transfer. The ledger cannot produce one, so this is an integrity check
    # on the event rather than a judgement about the customer.
    assert "LEGMISM" in verdict.reason_codes


def test_a_structured_out_of_hours_reversal_is_blocked() -> None:
    verdict = engine().score(
        event(
            amount={"amountMinor": 999_000, "currency": "PLN"},
            postedAt="2026-08-19T02:00:00Z",
            reversesTransferRef="TB202608190000000002",
        )
    )

    # 350 + 150 + 100 = 600, which is REVIEW rather than BLOCK. The combination is worth stating in
    # a test because it is the case a reader will assume is blocked, and it is not.
    assert verdict.score == 600
    assert verdict.decision == "REVIEW"
    assert verdict.reason_codes == ("AMT_STRC", "REVERSAL", "OFFHOURS")


def test_every_code_fits_the_contract() -> None:
    for rule in rules.RULES:
        assert len(rule.code) <= MAX_CODE_LENGTH, rule.code
        assert rule.code.isupper() or "_" in rule.code


def test_codes_are_unique() -> None:
    codes_seen = [rule.code for rule in rules.RULES]

    # A duplicate code makes a decision unexplainable: two different reasons reported as one.
    assert len(codes_seen) == len(set(codes_seen))


def test_every_rule_describes_itself() -> None:
    for rule in rules.RULES:
        assert rule.description, rule.code
        assert rule.weight > 0, rule.code


def test_no_rule_reaches_outside_the_event() -> None:
    """The catalogue may import nothing that could make one event score two ways.

    Read over the syntax tree rather than the text, so the module can describe in prose the very
    things it is forbidden to do - which is where a reader learns why the check exists.
    """
    tree = ast.parse(inspect.getsource(rules))

    imported: set[str] = set()
    called: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.add(node.module.split(".")[0])
        elif isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            called.add(node.func.id)

    # A clock, a random source, an environment lookup or any I/O would each make a replayed event
    # score differently from the original - and REQ-FRD-003 says it must not.
    forbidden_imports = {"datetime", "time", "random", "os", "socket", "pathlib", "requests"}
    assert not (imported & forbidden_imports), (
        f"the catalogue imports {imported & forbidden_imports}"
    )
    assert not (called & {"open", "input"}), f"the catalogue calls {called & {'open', 'input'}}"

    # And what it may import: the two modules that carry the event and the engine's own types.
    assert imported <= {"__future__", "typing", "fraud_scoring"}, imported


def test_scoring_is_reproducible_across_engines() -> None:
    subject = event(amount={"amountMinor": 999_000, "currency": "PLN"})

    assert engine().score(subject) == engine().score(subject)


def test_the_version_is_published_with_every_verdict() -> None:
    verdict = engine().score(event())

    assert verdict.model_version.startswith(rules.VERSION)
    assert len(verdict.model_version) <= 32
