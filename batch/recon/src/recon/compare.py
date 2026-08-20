"""The comparison: two ordered inputs, one pass, four ways for an account to be wrong.

**A match-merge, not two dictionaries.** ``ACCTPOST`` at stratum 0 match-merges because a 1995
address space could not hold the master, and CLAUDE.md records loading it as the mistake that passes
every test and destroys the point of the tier. That constraint is not this tier's - a 2026 batch job
has the memory. The shape is kept anyway, because a single ordered pass over two sorted inputs is
what stays correct when the estate outgrows the assumption, and because an ordered walk makes the
"missing on one side" cases fall out of the algorithm rather than out of a set difference computed
somewhere else.

**Nothing here can write.** ``compare`` takes two lists of records and returns breaks. It holds no
connection, no file handle and no path, so REQ-REC-003 - breaks are investigated, never
auto-corrected - is enforced by the shape of the code rather than by anybody's discipline. A future
change that wanted to auto-heal a break would have to add a writer to this module first, which is a
diff a reviewer notices.

**Classification.** Both sides present and agreeing is not a break. Both sides present and
disagreeing is *timing* when the master holds exactly what the cut-off says it should - the
remainder is then movements posted after the cut-off, which is expected - and *drift* otherwise. An
account on one side only is missing, named by the side that lacks it.

Timing is reported rather than suppressed. A difference that is invisible cannot be confirmed as
understood, and a reconciliation that hides its expected differences is one nobody can audit.
"""

from __future__ import annotations

import itertools
from collections.abc import Sequence
from dataclasses import dataclass
from enum import Enum

from recon.ledger import LedgerAccount
from recon.master import AccountRecord

__all__ = ["Break", "Classification", "ComparisonResult", "Totals", "compare"]


class Classification(Enum):
    """The four ways an account can break, as `TB-RECON-BREAKS-V1` names them."""

    VALUE_DRIFT = "VALUE_DRIFT"
    MISSING_ON_MASTER = "MISSING_ON_MASTER"
    MISSING_IN_LEDGER = "MISSING_IN_LEDGER"
    TIMING = "TIMING"


@dataclass(frozen=True, slots=True)
class Break:
    """One account the two systems do not agree about."""

    account_ref: str
    classification: Classification
    currency: str
    master_booked_minor: int | None
    ledger_booked_minor: int | None

    @property
    def difference_minor(self) -> int | None:
        """``master - ledger``, or ``None`` when one side has no figure at all.

        Deliberately not the present side's value when an account is missing from one system. A
        difference implies two figures were compared; printing one of them as a difference invites
        an operator to read a missing account as drift of that size.
        """
        if self.master_booked_minor is None or self.ledger_booked_minor is None:
            return None
        return self.master_booked_minor - self.ledger_booked_minor


@dataclass(frozen=True, slots=True)
class Totals:
    """The control totals. ``accounts_compared`` must equal matched plus broken."""

    accounts_compared: int
    accounts_matched: int
    accounts_broken: int
    total_absolute_drift_minor: int


@dataclass(frozen=True, slots=True)
class ComparisonResult:
    breaks: list[Break]
    totals: Totals


def compare(
    master_records: Sequence[AccountRecord],
    ledger_accounts: Sequence[LedgerAccount],
) -> ComparisonResult:
    """Walk both sides in account-reference order and classify every disagreement."""
    _require_ascending([record.account_ref for record in master_records], "master")
    _require_ascending([account.account_ref for account in ledger_accounts], "ledger")

    breaks: list[Break] = []
    compared = matched = 0

    left = right = 0
    while left < len(master_records) or right < len(ledger_accounts):
        on_master = master_records[left] if left < len(master_records) else None
        in_ledger = ledger_accounts[right] if right < len(ledger_accounts) else None

        if on_master is not None and (
            in_ledger is None or on_master.account_ref < in_ledger.account_ref
        ):
            compared += 1
            breaks.append(_missing_in_ledger(on_master))
            left += 1
            continue

        if in_ledger is not None and (
            on_master is None or in_ledger.account_ref < on_master.account_ref
        ):
            compared += 1
            breaks.append(_missing_on_master(in_ledger))
            right += 1
            continue

        # Both sides hold this account: the two branches above have already taken every case
        # where one of them is absent, so neither is None here.
        compared += 1
        found = _classify(on_master, in_ledger)
        if found is None:
            matched += 1
        else:
            breaks.append(found)
        left += 1
        right += 1

    drift = sum(abs(found.difference_minor or 0) for found in breaks)
    return ComparisonResult(
        breaks=breaks,
        totals=Totals(
            accounts_compared=compared,
            accounts_matched=matched,
            accounts_broken=len(breaks),
            total_absolute_drift_minor=drift,
        ),
    )


def _classify(on_master: AccountRecord, in_ledger: LedgerAccount) -> Break | None:
    """``None`` when the two agree. A currency disagreement is always drift."""
    same_currency = on_master.currency == in_ledger.currency
    if same_currency and on_master.booked_minor == in_ledger.booked_minor:
        return None

    # Timing: the master holds exactly what the cut-off says it should, so the whole of the
    # difference from the ledger is movements posted after the cycle's input file was cut.
    timing = same_currency and on_master.booked_minor == in_ledger.expected_minor
    return Break(
        account_ref=on_master.account_ref,
        classification=Classification.TIMING if timing else Classification.VALUE_DRIFT,
        currency=on_master.currency,
        master_booked_minor=on_master.booked_minor,
        ledger_booked_minor=in_ledger.booked_minor,
    )


def _missing_in_ledger(on_master: AccountRecord) -> Break:
    return Break(
        account_ref=on_master.account_ref,
        classification=Classification.MISSING_IN_LEDGER,
        currency=on_master.currency,
        master_booked_minor=on_master.booked_minor,
        ledger_booked_minor=None,
    )


def _missing_on_master(in_ledger: LedgerAccount) -> Break:
    return Break(
        account_ref=in_ledger.account_ref,
        classification=Classification.MISSING_ON_MASTER,
        currency=in_ledger.currency,
        master_booked_minor=None,
        ledger_booked_minor=in_ledger.booked_minor,
    )


def _require_ascending(refs: list[str], side: str) -> None:
    """A match-merge over an unordered input reports breaks that are an artefact of the order."""
    for previous, following in itertools.pairwise(refs):
        if previous >= following:
            raise ValueError(
                f"the {side} is not ascending by account reference: {previous!r} precedes "
                f"{following!r}. A match-merge over an unordered input invents breaks."
            )
