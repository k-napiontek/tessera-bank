"""Rule set version 1.

Every rule here reads the event and the parameters, and nothing else. No clock - the out-of-hours
rule uses the event's own ``postedAt``, so a message scored today and the same message replayed next
year reach the same answer. No randomness, no lookup, no memory. A test in ``test_rules.py`` reads
this file and fails if a clock, a random source or any I/O appears in it, because the rule that
breaks reproducibility will be added by somebody who has a good reason and has not read this
docstring.

The weights are deliberately coarse. This is a rule engine standing in for a model, and pretending
to three significant figures of risk would be a claim nobody can support. What each weight has to
get right is the ordering: a transfer that fires the structuring rule must outrank one that is
merely large.

**Changing a weight, a code or a threshold changes the version.** ``VERSION`` names the catalogue,
and the engine appends a digest of the parameters, so a decision made months ago says exactly what
produced it.
"""

from __future__ import annotations

from typing import Final

from fraud_scoring.events import TransferPosted
from fraud_scoring.scoring import Parameters, Rule, RuleSet

#: Bumped whenever a rule, a weight or a code changes. Dated rather than numbered, because the
#: question asked of it later is always "what were we running in August".
VERSION: Final = "rules-2026.08.1"

#: An amount within this fraction below the reporting threshold looks like structuring: a payment
#: deliberately sized to stay under a limit that would otherwise require a report.
STRUCTURING_BAND: Final = 0.05

#: Minor units. A round amount is only interesting when it is also a large one - rent and salaries
#: are round, and flagging them would bury the decisions that matter.
ROUND_UNIT_MINOR: Final = 100_000
ROUND_FLOOR_MINOR: Final = 1_000_000

#: The window, in UTC hours, in which a retail transfer is unremarkable.
BUSINESS_HOURS: Final = range(6, 22)


def _high_amount(event: TransferPosted, params: Parameters) -> bool:
    return event.amount.amount_minor >= params.high_amount_minor


def _structuring(event: TransferPosted, params: Parameters) -> bool:
    threshold = params.reporting_threshold_minor
    floor = int(threshold * (1 - STRUCTURING_BAND))
    # Below the threshold, not at or above it: a payment that meets the limit is reported, and the
    # whole point of structuring is to stay under it.
    return floor <= event.amount.amount_minor < threshold


def _round_amount(event: TransferPosted, params: Parameters) -> bool:
    amount = event.amount.amount_minor
    return amount >= ROUND_FLOOR_MINOR and amount % ROUND_UNIT_MINOR == 0


def _reversal(event: TransferPosted, params: Parameters) -> bool:
    return event.reverses_transfer_ref is not None


def _same_account(event: TransferPosted, params: Parameters) -> bool:
    # The ledger refuses a transfer between one account and itself, so this cannot happen. Its
    # appearance is therefore not a risky payment - it is evidence that something upstream is wrong,
    # and the weight is set high enough to be seen rather than averaged away.
    return event.debit_account_ref == event.credit_account_ref


def _out_of_hours(event: TransferPosted, params: Parameters) -> bool:
    # The event's own timestamp, normalised to UTC when it was parsed. Reading the wall clock here
    # would make the same message score differently depending on when it was replayed.
    return event.posted_at.hour not in BUSINESS_HOURS


def _legs_disagree(event: TransferPosted, params: Parameters) -> bool:
    """The two legs must be one debit and one credit, of the header's amount and currency."""
    directions = sorted(leg.direction for leg in event.movements)
    if directions != ["CREDIT", "DEBIT"]:
        return True
    return any(leg.amount != event.amount for leg in event.movements)


#: The catalogue. Order matters: reason codes are reported in it, so a decision reads the same way
#: every time.
RULES: Final = (
    Rule(
        code="AMT_STRC",
        weight=350,
        description="Amount sits just below the reporting threshold",
        fires=_structuring,
    ),
    Rule(
        code="AMT_HIGH",
        weight=300,
        description="Amount is at or above the high-value threshold",
        fires=_high_amount,
    ),
    Rule(
        code="AMT_RND",
        weight=100,
        description="Large amount, and an exactly round one",
        fires=_round_amount,
    ),
    Rule(
        code="REVERSAL",
        weight=150,
        description="Transfer reverses an earlier one",
        fires=_reversal,
    ),
    Rule(
        code="SELFPAY",
        weight=500,
        description="Debit and credit name the same account, which the ledger forbids",
        fires=_same_account,
    ),
    Rule(
        code="OFFHOURS",
        weight=100,
        description="Posted outside business hours, by the event's own timestamp",
        fires=_out_of_hours,
    ),
    Rule(
        code="LEGMISM",
        weight=400,
        description="The postings do not match the transfer they belong to",
        fires=_legs_disagree,
    ),
)

RULE_SET: Final = RuleSet(version=VERSION, rules=RULES)
