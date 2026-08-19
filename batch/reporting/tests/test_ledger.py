"""The reader, against real PostgreSQL with the ledger's own migrations applied.

Every test here is about one property: what a run sees is fixed by the position it was cut at, and
by nothing else. An in-memory double could not make that claim - the position is a fact about commit
order in a real database.
"""

from __future__ import annotations

import datetime as dt

import psycopg
import pytest

from reporting.ledger import GENESIS_HASH, LedgerReader, Position

BUSINESS_DATE = dt.date(2026, 8, 18)


def read(dsn: str) -> LedgerReader:
    return LedgerReader(psycopg.connect(dsn), query_timeout_seconds=30)


def test_an_empty_ledger_has_position_zero_at_the_genesis_hash(dsn, ledger) -> None:
    with read(dsn) as reader:
        assert reader.resolve_position() == Position(seq=0, chain_hash=GENESIS_HASH)


def test_the_position_is_the_audit_high_water_mark(dsn, ledger) -> None:
    ledger.open_account("ACC-0000000001")
    ledger.open_account("ACC-0000000002")
    last = ledger.post_transfer(
        "TB202608180000000001",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=25_000,
        value_date="2026-08-18",
    )

    with read(dsn) as reader:
        position = reader.resolve_position()

    assert position.seq == last
    assert position.chain_hash != GENESIS_HASH


def test_a_recorded_position_can_be_resolved_again(dsn, ledger) -> None:
    ledger.open_account("ACC-0000000001")
    ledger.open_account("ACC-0000000002")
    cut = ledger.post_transfer(
        "TB202608180000000001",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=25_000,
        value_date="2026-08-18",
    )
    with read(dsn) as reader:
        at_cut = reader.resolve_position()

    ledger.post_transfer(
        "TB202608180000000002",
        debit="ACC-0000000002",
        credit="ACC-0000000001",
        amount_minor=1,
        value_date="2026-08-18",
    )

    with read(dsn) as reader:
        assert reader.resolve_position(cut) == at_cut
        assert reader.resolve_position().seq > cut


def test_a_position_beyond_the_chain_is_refused(dsn, ledger) -> None:
    # Reproducing a run against a database that does not contain it must fail loudly. Silently
    # reporting the whole ledger instead would produce a plausible file for the wrong cut.
    with read(dsn) as reader, pytest.raises(LookupError):
        reader.resolve_position(9_999)


def test_accounts_are_bounded_by_the_position(dsn, ledger) -> None:
    ledger.open_account("ACC-0000000001")
    ledger.open_account("ACC-0000000002")
    with read(dsn) as reader:
        cut = reader.resolve_position()

    ledger.open_account("ACC-0000000003")

    with read(dsn) as reader:
        after_cut = reader.accounts_as_at(BUSINESS_DATE, cut)
        now = reader.accounts_as_at(BUSINESS_DATE, reader.resolve_position())

    assert [account.account_ref for account in after_cut] == ["ACC-0000000001", "ACC-0000000002"]
    assert len(now) == 3


def test_an_account_opened_after_the_business_date_is_not_reported(dsn, ledger) -> None:
    ledger.open_account("ACC-0000000001", opened_date="2026-08-18")
    ledger.open_account("ACC-0000000002", opened_date="2026-08-19")

    with read(dsn) as reader:
        accounts = reader.accounts_as_at(BUSINESS_DATE, reader.resolve_position())

    assert [account.account_ref for account in accounts] == ["ACC-0000000001"]


def test_totals_are_summed_from_postings_within_the_position(dsn, ledger) -> None:
    ledger.open_account("ACC-0000000001")
    ledger.open_account("ACC-0000000002")
    ledger.post_transfer(
        "TB202608180000000001",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=25_000,
        value_date="2026-08-18",
    )
    with read(dsn) as reader:
        cut = reader.resolve_position()

    ledger.post_transfer(
        "TB202608180000000002",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=7_000,
        value_date="2026-08-18",
    )

    with read(dsn) as reader:
        accounts = {a.account_ref: a for a in reader.accounts_as_at(BUSINESS_DATE, cut)}

    assert accounts["ACC-0000000001"].debit_minor == 25_000
    assert accounts["ACC-0000000001"].credit_minor == 0
    assert accounts["ACC-0000000001"].movement_count == 1
    assert accounts["ACC-0000000002"].credit_minor == 25_000


def test_a_future_value_date_is_not_in_todays_position(dsn, ledger) -> None:
    ledger.open_account("ACC-0000000001")
    ledger.open_account("ACC-0000000002")
    ledger.post_transfer(
        "TB202608190000000001",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=9_900,
        value_date="2026-08-19",
    )

    with read(dsn) as reader:
        position = reader.resolve_position()
        accounts = {a.account_ref: a for a in reader.accounts_as_at(BUSINESS_DATE, position)}

    assert accounts["ACC-0000000001"].debit_minor == 0


def test_movements_are_the_postings_of_the_business_date_only(dsn, ledger) -> None:
    ledger.open_account("ACC-0000000001")
    ledger.open_account("ACC-0000000002")
    ledger.post_transfer(
        "TB202608170000000001",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=100,
        value_date="2026-08-17",
    )
    ledger.post_transfer(
        "TB202608180000000001",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=25_000,
        value_date="2026-08-18",
    )

    with read(dsn) as reader:
        movements = reader.movements_on(BUSINESS_DATE, reader.resolve_position())

    assert [(m.entry_ref, m.seq, m.direction, m.amount_minor) for m in movements] == [
        ("TB202608180000000001", 1, "DEBIT", 25_000),
        ("TB202608180000000001", 2, "CREDIT", 25_000),
    ]


def test_the_reader_cannot_write(dsn, ledger) -> None:
    # A reporting job that can write to the ledger is a reporting job that eventually will.
    with read(dsn) as reader, pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
        reader.execute_for_test(
            "INSERT INTO account (reference, customer_ref, account_type, currency, status) "
            "VALUES ('X', 'Y', 'ASSET', 'PLN', 'OPEN')"
        )


def test_the_materialised_balance_is_readable_for_reconciliation_only(dsn, ledger) -> None:
    ledger.open_account("ACC-0000000001")
    ledger.open_account("ACC-0000000002")
    ledger.post_transfer(
        "TB202608180000000001",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=25_000,
        value_date="2026-08-18",
    )

    with read(dsn) as reader:
        materialised = reader.materialised_balances()

    assert materialised["ACC-0000000001"] == -25_000
    assert materialised["ACC-0000000002"] == 25_000
