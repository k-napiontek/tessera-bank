"""The comparison and its classification. No database: two lists in, breaks out.

The classification is the part of this package that decides whether it is worth having. A
reconciliation that calls a post-cut-off movement "drift" trains operators to ignore the report,
and an ignored report is worse than no report because it looks like a control.
"""

from __future__ import annotations

import pytest

from recon.compare import Classification, compare
from recon.ledger import LedgerAccount
from recon.master import AccountRecord


def master(ref: str, booked: int, *, currency: str = "PLN") -> AccountRecord:
    return AccountRecord(
        account_ref=ref,
        customer_ref="CU0000000001",
        account_type="LIABILITY",
        currency=currency,
        status="OPEN",
        booked_minor=booked,
        available_minor=booked,
        opened_date="20260101",
        last_move_date="20260818",
    )


def led(
    ref: str, booked: int, expected: int | None = None, *, currency: str = "PLN"
) -> LedgerAccount:
    return LedgerAccount(
        account_ref=ref,
        account_type="LIABILITY",
        currency=currency,
        status="OPEN",
        booked_minor=booked,
        expected_minor=booked if expected is None else expected,
    )


def test_agreeing_balances_produce_no_break() -> None:
    result = compare([master("TB00000000000001", -25_000)], [led("TB00000000000001", -25_000)])
    assert result.breaks == []
    assert result.totals.accounts_compared == 1
    assert result.totals.accounts_matched == 1
    assert result.totals.accounts_broken == 0


def test_a_value_discrepancy_is_drift() -> None:
    result = compare([master("TB00000000000001", -25_000)], [led("TB00000000000001", -24_000)])
    (found,) = result.breaks
    assert found.classification is Classification.VALUE_DRIFT
    assert found.master_booked_minor == -25_000
    assert found.ledger_booked_minor == -24_000
    assert found.difference_minor == -1_000


def test_a_post_cut_off_movement_is_timing_not_drift() -> None:
    """The master holds exactly what the cut-off says it should; the rest arrived after it.

    This is the case the package exists for. If this test can be made to pass while the master
    disagrees with `expected_minor`, the classification is worthless.
    """
    result = compare(
        [master("TB00000000000001", -10_000)],
        [led("TB00000000000001", booked=-17_000, expected=-10_000)],
    )
    (found,) = result.breaks
    assert found.classification is Classification.TIMING
    assert found.difference_minor == 7_000


def test_drift_wins_when_the_master_matches_neither_figure() -> None:
    """A master that agrees with nothing is drift, even where timing is also in play."""
    result = compare(
        [master("TB00000000000001", -9_999)],
        [led("TB00000000000001", booked=-17_000, expected=-10_000)],
    )
    (found,) = result.breaks
    assert found.classification is Classification.VALUE_DRIFT


def test_an_account_only_the_ledger_has() -> None:
    result = compare([], [led("TB00000000000001", -500)])
    (found,) = result.breaks
    assert found.classification is Classification.MISSING_ON_MASTER
    assert found.master_booked_minor is None
    assert found.ledger_booked_minor == -500
    assert found.difference_minor is None, "one figure is not a difference"


def test_an_account_only_the_master_has() -> None:
    result = compare([master("TB00000000000001", 700)], [])
    (found,) = result.breaks
    assert found.classification is Classification.MISSING_IN_LEDGER
    assert found.ledger_booked_minor is None
    assert found.difference_minor is None


def test_the_match_merge_walks_both_sides_in_order() -> None:
    """Interleaved, with a gap on each side, in one ordered pass."""
    masters = [master(f"TB0000000000000{n}", 0) for n in (1, 2, 4, 5)]
    ledgers = [led(f"TB0000000000000{n}", 0) for n in (2, 3, 4, 6)]
    result = compare(masters, ledgers)

    found = {b.account_ref: b.classification for b in result.breaks}
    assert found == {
        "TB00000000000001": Classification.MISSING_IN_LEDGER,
        "TB00000000000003": Classification.MISSING_ON_MASTER,
        "TB00000000000005": Classification.MISSING_IN_LEDGER,
        "TB00000000000006": Classification.MISSING_ON_MASTER,
    }
    assert result.totals.accounts_compared == 6
    assert result.totals.accounts_matched == 2


def test_breaks_come_back_ascending_by_account_reference() -> None:
    masters = [master(f"TB0000000000000{n}", n) for n in (1, 2, 3)]
    ledgers = [led(f"TB0000000000000{n}", 0) for n in (1, 2, 3)]
    refs = [b.account_ref for b in compare(masters, ledgers).breaks]
    assert refs == sorted(refs)


def test_the_control_totals_balance() -> None:
    masters = [master(f"TB0000000000000{n}", 100) for n in (1, 2, 3)]
    ledgers = [
        led("TB00000000000001", 100),
        led("TB00000000000002", 99),
        led("TB00000000000004", 5),
    ]
    totals = compare(masters, ledgers).totals
    assert totals.accounts_compared == totals.accounts_matched + totals.accounts_broken


def test_absolute_drift_does_not_cancel() -> None:
    """Equal and opposite errors on two accounts is the most alarming shape, not the least."""
    masters = [master("TB00000000000001", 100), master("TB00000000000002", -100)]
    ledgers = [led("TB00000000000001", 0), led("TB00000000000002", 0)]
    assert compare(masters, ledgers).totals.total_absolute_drift_minor == 200


def test_an_unsorted_master_is_refused_rather_than_quietly_mismatched() -> None:
    """A match-merge over an unordered input reports breaks that are an artefact of the order."""
    masters = [master("TB00000000000002", 0), master("TB00000000000001", 0)]
    with pytest.raises(ValueError, match="ascending"):
        compare(masters, [])


def test_a_currency_disagreement_is_drift_even_when_the_numbers_agree() -> None:
    """Same number, different currency, is not the same money."""
    result = compare(
        [master("TB00000000000001", 100, currency="PLN")],
        [led("TB00000000000001", 100, currency="EUR")],
    )
    (found,) = result.breaks
    assert found.classification is Classification.VALUE_DRIFT
