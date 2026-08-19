"""The movement summary: every posting of one business date, and what they add up to.

The control here is the double-entry identity. Total debits equal total credits, per currency, for
any set of complete entries - so a currency where they do not is a currency where a posting went
missing between the query and the file. That is the only check available at this point that would
notice, and it is why the report raises rather than printing an imbalance as a figure.
"""

from __future__ import annotations

import datetime as dt

import pytest

from reporting.ledger import Movement, Position
from reporting.money import Money
from reporting.movement_report import MovementReport, render

BUSINESS_DATE = dt.date(2026, 8, 18)
POSITION = Position(seq=12, chain_hash="a" * 64)


def leg(
    entry: str,
    seq: int,
    account: str,
    direction: str,
    amount: int,
    *,
    currency: str = "PLN",
    account_type: str = "LIABILITY",
) -> Movement:
    return Movement(
        entry_ref=entry,
        seq=seq,
        account_ref=account,
        account_type=account_type,
        direction=direction,
        amount_minor=amount,
        currency=currency,
        value_date=BUSINESS_DATE,
    )


def balanced_pair(entry: str, amount: int, currency: str = "PLN") -> list[Movement]:
    return [
        leg(entry, 1, "ACC-0000000001", "DEBIT", amount, currency=currency),
        leg(entry, 2, "ACC-0000000002", "CREDIT", amount, currency=currency),
    ]


def test_movements_keep_the_order_they_were_read_in() -> None:
    report = MovementReport.of(
        BUSINESS_DATE, POSITION, balanced_pair("TB202608180000000001", 25_000)
    )

    assert [(m.entry_ref, m.seq) for m in report.movements] == [
        ("TB202608180000000001", 1),
        ("TB202608180000000001", 2),
    ]


def test_control_totals_count_and_sum_each_side_per_currency() -> None:
    report = MovementReport.of(
        BUSINESS_DATE,
        POSITION,
        balanced_pair("TB202608180000000001", 25_000)
        + balanced_pair("TB202608180000000002", 7_000)
        + balanced_pair("TB202608180000000003", 700, currency="JPY"),
    )

    totals = {total.currency: total for total in report.totals}
    assert totals["PLN"].debit_count == 2
    assert totals["PLN"].credit_count == 2
    assert totals["PLN"].debit == Money(32_000, "PLN")
    assert totals["PLN"].credit == Money(32_000, "PLN")
    assert totals["JPY"].debit == Money(700, "JPY")


def test_an_imbalanced_currency_fails_the_report() -> None:
    with pytest.raises(ValueError, match="does not balance"):
        MovementReport.of(
            BUSINESS_DATE,
            POSITION,
            [leg("TB202608180000000001", 1, "ACC-0000000001", "DEBIT", 25_000)],
        )


def test_a_day_with_no_movement_is_a_report_with_no_totals() -> None:
    # A quiet day is a valid day. An empty file with a header is the honest way to say so; skipping
    # the run would leave a gap that reads as a missing report rather than as nothing happening.
    report = MovementReport.of(BUSINESS_DATE, POSITION, [])

    assert report.movements == ()
    assert report.totals == ()
    assert render(report).splitlines() == [
        "record,entryRef,leg,accountRef,accountType,direction,currency,amountMinor,amount,count"
    ]


def test_the_rendered_report_puts_each_sides_total_on_its_own_row() -> None:
    report = MovementReport.of(
        BUSINESS_DATE, POSITION, balanced_pair("TB202608180000000001", 25_000)
    )

    lines = render(report).splitlines()

    assert lines[1] == (
        "MOVEMENT,TB202608180000000001,1,ACC-0000000001,LIABILITY,DEBIT,PLN,25000,250.00,"
    )
    assert lines[3] == "TOTAL,,,,,DEBIT,PLN,25000,250.00,1"
    assert lines[4] == "TOTAL,,,,,CREDIT,PLN,25000,250.00,1"


def test_the_rendered_report_has_no_carriage_returns() -> None:
    rendered = render(MovementReport.of(BUSINESS_DATE, POSITION, balanced_pair("TB1", 1)))

    assert "\r" not in rendered
    assert rendered.endswith("\n")


def test_the_report_never_carries_the_remittance_reference() -> None:
    # journal_entry.reference_text is free text a customer wrote. The ledger deliberately keeps it
    # out of its audit rows, and a report retained for years is a worse place for it still. Movement
    # has no field for it, and this asserts that stays true.
    assert "reference" not in {field.lower() for field in Movement.__slots__}
