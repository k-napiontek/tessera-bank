"""The daily position report, and the accounting rule underneath it.

The rule this file exists to pin down: which way an account moves depends on the normal balance of
its type. A customer's current account is a liability of the bank, so a credit increases it; the
bank's cash is an asset, so a debit increases it. Getting this backwards produces a system that
appears to work right up until the balance sheet is drawn, which is the reason the ledger states it
in exactly one place and this tier does not restate it in SQL.
"""

from __future__ import annotations

import ast
import datetime as dt
import pathlib

import pytest

from reporting.accounting import NORMAL_BALANCE, booked_balance
from reporting.ledger import AccountPosition, Position
from reporting.money import Money
from reporting.position_report import PositionReport, render

BUSINESS_DATE = dt.date(2026, 8, 18)
POSITION = Position(seq=12, chain_hash="a" * 64)


def account(
    reference: str,
    *,
    account_type: str = "LIABILITY",
    currency: str = "PLN",
    debit: int = 0,
    credit: int = 0,
    movements: int = 0,
) -> AccountPosition:
    return AccountPosition(
        account_ref=reference,
        customer_ref="CUST0000000000000001",
        account_type=account_type,
        currency=currency,
        status="OPEN",
        opened_date=dt.date(2026, 1, 1),
        debit_minor=debit,
        credit_minor=credit,
        movement_count=movements,
    )


def test_every_account_type_has_a_normal_balance() -> None:
    assert NORMAL_BALANCE == {
        "ASSET": "DEBIT",
        "LIABILITY": "CREDIT",
        "EQUITY": "CREDIT",
        "REVENUE": "CREDIT",
        "EXPENSE": "DEBIT",
    }


def test_a_liability_increases_on_the_credit_side() -> None:
    # The customer's money. The bank owes more of it after a credit.
    assert booked_balance(account("A", account_type="LIABILITY", credit=25_000)) == Money(
        25_000, "PLN"
    )
    assert booked_balance(account("A", account_type="LIABILITY", debit=25_000)) == Money(
        -25_000, "PLN"
    )


def test_an_asset_increases_on_the_debit_side() -> None:
    assert booked_balance(account("A", account_type="ASSET", debit=25_000)) == Money(25_000, "PLN")
    assert booked_balance(account("A", account_type="ASSET", credit=25_000)) == Money(
        -25_000, "PLN"
    )


def test_an_unknown_account_type_is_refused() -> None:
    with pytest.raises(KeyError):
        booked_balance(account("A", account_type="GOODWILL"))


def test_the_report_carries_one_row_per_account_in_reference_order() -> None:
    report = PositionReport.of(
        BUSINESS_DATE,
        POSITION,
        [
            account("ACC-0000000002", credit=25_000, movements=1),
            account("ACC-0000000001", debit=25_000, movements=1),
        ],
    )

    assert [row.account_ref for row in report.accounts] == ["ACC-0000000001", "ACC-0000000002"]


def test_control_totals_are_per_currency_and_debits_equal_credits() -> None:
    report = PositionReport.of(
        BUSINESS_DATE,
        POSITION,
        [
            account("ACC-0000000001", debit=25_000, movements=1),
            account("ACC-0000000002", credit=25_000, movements=1),
            account("ACC-0000000003", currency="JPY", debit=700, movements=1),
            account("ACC-0000000004", currency="JPY", credit=700, movements=1),
        ],
    )

    totals = {total.currency: total for total in report.totals}
    assert totals["PLN"].debit == Money(25_000, "PLN")
    assert totals["PLN"].credit == Money(25_000, "PLN")
    assert totals["PLN"].accounts == 2
    assert totals["PLN"].movements == 2
    assert totals["JPY"].debit == Money(700, "JPY")


def test_a_currency_whose_debits_and_credits_disagree_fails_the_report() -> None:
    # Double entry says they cannot. If they do, a posting was lost between the query and the file,
    # and printing the report anyway would publish the loss as a figure.
    with pytest.raises(ValueError, match="does not balance"):
        PositionReport.of(
            BUSINESS_DATE,
            POSITION,
            [account("ACC-0000000001", debit=25_000, movements=1)],
        )


def test_the_rendered_report_is_csv_with_totals_after_the_detail() -> None:
    report = PositionReport.of(
        BUSINESS_DATE,
        POSITION,
        [
            account("ACC-0000000001", debit=25_000, movements=1),
            account("ACC-0000000002", credit=25_000, movements=1),
        ],
    )

    lines = render(report).splitlines()

    assert lines[0] == (
        "record,accountRef,customerRef,accountType,currency,status,"
        "debitMinor,creditMinor,bookedMinor,bookedAmount,movementCount"
    )
    assert lines[1] == (
        "ACCOUNT,ACC-0000000001,CUST0000000000000001,LIABILITY,PLN,OPEN,25000,0,-25000,-250.00,1"
    )
    assert lines[3] == "TOTAL,,,,PLN,,25000,25000,0,0.00,2"


def test_the_rendered_report_ends_with_a_newline_and_no_carriage_returns() -> None:
    # csv.writer defaults to \r\n, which would make the file differ byte for byte between a run on
    # this machine and a run anywhere else that opened it in text mode.
    rendered = render(PositionReport.of(BUSINESS_DATE, POSITION, []))

    assert "\r" not in rendered
    assert rendered.endswith("\n")


def test_the_report_holds_no_wall_clock() -> None:
    # A generation timestamp would make byte-identical reruns impossible by construction. The run
    # instant belongs in the manifest beside the file, never in it. Checked by parsing the module
    # rather than its output, because output only shows the clocks that happened to fire.
    source = (
        pathlib.Path(__file__).resolve().parents[1] / "src" / "reporting" / "position_report.py"
    )
    tree = ast.parse(source.read_text(encoding="utf-8"))

    imported = {
        alias.name.split(".")[0]
        for node in ast.walk(tree)
        if isinstance(node, ast.Import)
        for alias in node.names
    } | {
        node.module.split(".")[0]
        for node in ast.walk(tree)
        if isinstance(node, ast.ImportFrom) and node.module
    }

    assert "time" not in imported
    assert "datetime" not in imported
