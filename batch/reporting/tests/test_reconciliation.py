"""The report's arithmetic against the ledger's own figure, on real PostgreSQL.

REQ-REP-003 says report totals reconcile independently to the ledger. Independently is the load-
bearing word: in production the report sums postings in Python and applies the normal-balance rule
from ``accounting.py``, while the ledger maintains ``balance.booked_minor`` in Java through
``AccountType.signedEffect``. Two implementations of one rule, required to agree.

Here the ledger's Java is not running, so the fixture maintains ``balance`` with its own
reproduction of that rule - a separate code path from ``accounting.py``, but written in this
repository, and a shared misreading of the rule would fool both. So the sign convention is *also*
pinned by hand-computed figures for all five account types, which no shared code path can fake.
"""

from __future__ import annotations

import datetime as dt

import psycopg

from reporting.accounting import booked_balance
from reporting.ledger import LedgerReader
from reporting.position_report import PositionReport

BUSINESS_DATE = dt.date(2026, 8, 18)


def test_the_reports_balances_agree_with_the_ledgers_own(dsn, ledger) -> None:
    # A mixed set of types on purpose. With liabilities alone the sign convention cancels out and a
    # report that had it backwards would reconcile perfectly.
    ledger.open_account("ACC-0000000001", account_type="LIABILITY")
    ledger.open_account("ACC-0000000002", account_type="ASSET")
    ledger.open_account("ACC-0000000003", account_type="REVENUE")
    ledger.open_account("ACC-0000000004", account_type="EXPENSE")

    ledger.post_transfer(
        "TB202608180000000001",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=25_000,
        value_date="2026-08-18",
    )
    ledger.post_transfer(
        "TB202608180000000002",
        debit="ACC-0000000004",
        credit="ACC-0000000003",
        amount_minor=1_750,
        value_date="2026-08-18",
    )
    ledger.post_transfer(
        "TB202608170000000001",
        debit="ACC-0000000002",
        credit="ACC-0000000001",
        amount_minor=400,
        value_date="2026-08-17",
    )

    with LedgerReader(psycopg.connect(dsn), query_timeout_seconds=30) as reader:
        position = reader.resolve_position()
        report = PositionReport.of(
            BUSINESS_DATE, position, reader.accounts_as_at(BUSINESS_DATE, position)
        )
        materialised = reader.materialised_balances()

    computed = {account.account_ref: booked_balance(account).minor for account in report.accounts}

    assert computed == materialised

    # Worked by hand from the postings above. ACC-1 is debited 25 000 and credited 400; a liability
    # rises on the credit, so it stands at 400 - 25 000. ACC-2 is credited 25 000 and debited 400;
    # an asset rises on the debit, so it stands at that same figure from the other side - which is
    # the accounting identity, and the reason a two-legged transfer alone cannot pin the convention.
    # ACC-3 and ACC-4 are what pin it: a revenue credited and an expense debited both rise.
    assert computed["ACC-0000000001"] == -24_600
    assert computed["ACC-0000000002"] == -24_600
    assert computed["ACC-0000000003"] == 1_750
    assert computed["ACC-0000000004"] == 1_750


def test_the_control_totals_reconcile_to_a_direct_ledger_query(dsn, ledger) -> None:
    ledger.open_account("ACC-0000000001", account_type="LIABILITY")
    ledger.open_account("ACC-0000000002", account_type="ASSET")
    ledger.post_transfer(
        "TB202608180000000001",
        debit="ACC-0000000001",
        credit="ACC-0000000002",
        amount_minor=25_000,
        value_date="2026-08-18",
    )

    with LedgerReader(psycopg.connect(dsn), query_timeout_seconds=30) as reader:
        position = reader.resolve_position()
        report = PositionReport.of(
            BUSINESS_DATE, position, reader.accounts_as_at(BUSINESS_DATE, position)
        )

    with psycopg.connect(dsn) as direct:
        row = direct.execute(
            """
            SELECT coalesce(sum(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
                   coalesce(sum(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0)
              FROM posting p
              JOIN journal_entry je ON je.reference = p.entry_ref
             WHERE je.value_date <= %s AND p.currency = 'PLN'
            """,
            (BUSINESS_DATE,),
        ).fetchone()

    total = next(total for total in report.totals if total.currency == "PLN")
    assert (total.debit.minor, total.credit.minor) == (row[0], row[1])
