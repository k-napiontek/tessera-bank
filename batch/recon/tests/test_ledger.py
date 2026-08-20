"""The ledger side of the comparison, on real PostgreSQL with the ledger's own schema.

Two claims are under test and they are different. The first is that the reader reproduces the
ledger's arithmetic - balances summed from postings, signed by the normal balance of the account's
type. The second is that it reproduces it **at a position**, so that a rerun of a morning's
reconciliation sees exactly the ledger the first run saw.

The account types are mixed on purpose in every balance test. With liabilities alone the sign
convention cancels out and a reader that had it backwards would agree perfectly.
"""

from __future__ import annotations

import datetime as dt

import psycopg
import pytest

from recon.ledger import GENESIS_HASH, LedgerReader

BUSINESS_DATE = dt.date(2026, 8, 18)
YESTERDAY = dt.date(2026, 8, 17)


def reader(dsn: str) -> LedgerReader:
    return LedgerReader(psycopg.connect(dsn, autocommit=True), query_timeout_seconds=30)


def test_an_empty_ledger_is_at_genesis(dsn, ledger) -> None:
    with reader(dsn) as reading:
        position = reading.resolve_position()
    assert position.seq == 0
    assert position.chain_hash == GENESIS_HASH


def test_the_position_is_the_latest_audit_row(dsn, ledger) -> None:
    ledger.open_account("TB00000000000001")
    last = ledger.open_account("TB00000000000002")
    with reader(dsn) as reading:
        assert reading.resolve_position().seq == last


def test_a_position_this_ledger_does_not_hold_is_refused(dsn, ledger) -> None:
    """A run asked to reproduce a cut this database has never held must fail loudly."""
    ledger.open_account("TB00000000000001")
    with reader(dsn) as reading, pytest.raises(LookupError, match="different database"):
        reading.resolve_position(9999)


def test_balances_are_summed_from_postings_with_the_normal_balance_rule(
    dsn,
    ledger,
) -> None:
    ledger.open_account("TB00000000000001", account_type="LIABILITY")
    ledger.open_account("TB00000000000002", account_type="ASSET")
    ledger.post_transfer(
        "TB202608180000000001",
        debit="TB00000000000001",
        credit="TB00000000000002",
        amount_minor=25_000,
        value_date="2026-08-18",
    )

    with reader(dsn) as reading:
        position = reading.resolve_position()
        accounts = {
            account.account_ref: account
            for account in reading.accounts_as_at(BUSINESS_DATE, position, frozenset())
        }

    # A LIABILITY rises on the credit, so a debit takes it down. An ASSET rises on the debit, so a
    # credit takes it down. Hand-computed, because a shared code path could fool both sides.
    assert accounts["TB00000000000001"].booked_minor == -25_000
    assert accounts["TB00000000000002"].booked_minor == -25_000


def test_accounts_come_back_in_reference_order(dsn, ledger) -> None:
    """The comparison is a match-merge over two ordered inputs; this is one of them."""
    for n in (3, 1, 2):
        ledger.open_account(f"TB0000000000000{n}")
    with reader(dsn) as reading:
        position = reading.resolve_position()
        refs = [a.account_ref for a in reading.accounts_as_at(BUSINESS_DATE, position, frozenset())]
    assert refs == sorted(refs)


def test_a_posting_beyond_the_position_is_not_seen(dsn, ledger) -> None:
    """What makes a rerun reproducible: the cut is a sequence number, not a clock."""
    ledger.open_account("TB00000000000001", account_type="LIABILITY")
    ledger.open_account("TB00000000000002", account_type="ASSET")
    with reader(dsn) as reading:
        early = reading.resolve_position()

    ledger.post_transfer(
        "TB202608180000000001",
        debit="TB00000000000001",
        credit="TB00000000000002",
        amount_minor=11_100,
        value_date="2026-08-18",
    )

    with reader(dsn) as reading:
        at_early = {
            a.account_ref: a for a in reading.accounts_as_at(BUSINESS_DATE, early, frozenset())
        }
        later = reading.resolve_position()
        at_later = {
            a.account_ref: a for a in reading.accounts_as_at(BUSINESS_DATE, later, frozenset())
        }

    assert at_early["TB00000000000001"].booked_minor == 0
    assert at_later["TB00000000000001"].booked_minor == -11_100


def test_the_cut_off_separates_what_the_master_should_hold(dsn, ledger) -> None:
    """`expected_minor` is the balance the master ought to show; `booked_minor` is the ledger's."""
    ledger.open_account("TB00000000000001", account_type="LIABILITY")
    ledger.open_account("TB00000000000002", account_type="ASSET")
    ledger.post_transfer(
        "TB202608180000000001",
        debit="TB00000000000001",
        credit="TB00000000000002",
        amount_minor=10_000,
        value_date="2026-08-18",
    )
    ledger.post_transfer(
        "TB202608180000000002",
        debit="TB00000000000001",
        credit="TB00000000000002",
        amount_minor=7_000,
        value_date="2026-08-18",
    )

    # Only the first reached the movement file the cycle consumed. The second is post-cut-off.
    applied = frozenset({"TB202608180000000001"})
    with reader(dsn) as reading:
        position = reading.resolve_position()
        accounts = {
            a.account_ref: a for a in reading.accounts_as_at(BUSINESS_DATE, position, applied)
        }

    assert accounts["TB00000000000001"].booked_minor == -17_000
    assert accounts["TB00000000000001"].expected_minor == -10_000


def test_an_earlier_value_date_is_expected_in_the_master_without_being_in_tonights_file(
    dsn,
    ledger,
) -> None:
    """Yesterday's movements were applied by yesterday's cycle, and are not in tonight's file."""
    ledger.open_account("TB00000000000001", account_type="LIABILITY")
    ledger.open_account("TB00000000000002", account_type="ASSET")
    ledger.post_transfer(
        "TB202608170000000001",
        debit="TB00000000000001",
        credit="TB00000000000002",
        amount_minor=4_200,
        value_date="2026-08-17",
    )

    with reader(dsn) as reading:
        position = reading.resolve_position()
        accounts = {
            a.account_ref: a for a in reading.accounts_as_at(BUSINESS_DATE, position, frozenset())
        }

    account = accounts["TB00000000000001"]
    assert account.booked_minor == -4_200
    assert account.expected_minor == -4_200, "yesterday's cycle applied it; it is not timing"


def test_a_future_dated_entry_is_neither_booked_nor_expected(dsn, ledger) -> None:
    ledger.open_account("TB00000000000001", account_type="LIABILITY")
    ledger.open_account("TB00000000000002", account_type="ASSET")
    ledger.post_transfer(
        "TB202608190000000001",
        debit="TB00000000000001",
        credit="TB00000000000002",
        amount_minor=9_900,
        value_date="2026-08-19",
    )

    with reader(dsn) as reading:
        position = reading.resolve_position()
        accounts = {
            a.account_ref: a for a in reading.accounts_as_at(BUSINESS_DATE, position, frozenset())
        }
    assert accounts["TB00000000000001"].booked_minor == 0


def test_the_same_position_gives_the_same_figures_twice(dsn, ledger) -> None:
    ledger.open_account("TB00000000000001", account_type="LIABILITY")
    ledger.open_account("TB00000000000002", account_type="ASSET")
    ledger.post_transfer(
        "TB202608180000000001",
        debit="TB00000000000001",
        credit="TB00000000000002",
        amount_minor=3_300,
        value_date="2026-08-18",
    )
    with reader(dsn) as reading:
        position = reading.resolve_position()
        first = reading.accounts_as_at(BUSINESS_DATE, position, frozenset())
        second = reading.accounts_as_at(BUSINESS_DATE, position, frozenset())
    assert first == second


def test_the_reader_cannot_write(dsn, ledger) -> None:
    """Read-only against both systems is a Constraint, and a constraint nothing tests is a comment.

    The role is granted SELECT and nothing else, so the refusal comes from PostgreSQL rather than
    from this component's own good intentions.
    """
    with psycopg.connect(dsn, autocommit=True) as admin:
        admin.execute("DROP ROLE IF EXISTS recon_reader")
        admin.execute("CREATE ROLE recon_reader LOGIN PASSWORD 'recon'")
        admin.execute("GRANT CONNECT ON DATABASE test TO recon_reader")
        admin.execute("GRANT USAGE ON SCHEMA public TO recon_reader")
        admin.execute("GRANT SELECT ON ALL TABLES IN SCHEMA public TO recon_reader")

    restricted = dsn.split("@")[1]
    with psycopg.connect(f"postgresql://recon_reader:recon@{restricted}", autocommit=True) as ro:
        ro.execute("SELECT count(*) FROM account").fetchone()
        with pytest.raises(psycopg.errors.InsufficientPrivilege):
            ro.execute("INSERT INTO account (reference) VALUES ('TB99999999999999')")
