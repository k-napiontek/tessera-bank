"""The daily position report: where every account stood at the close of a business date.

Built from postings, never from the ledger's ``balance`` table. The balance table reflects *now*;
this report has to reflect a business date and a position, and a report built on mutable state is
not auditable - which is the whole reason this component exists. It also makes the reconciliation
independent: the report's arithmetic and the ledger's materialised figure are two computations, and
a test asserts they agree rather than assuming it.

**Debits must equal credits, per currency.** That is not a nice property, it is the definition of
double entry, and it is the only check available here that would notice a posting lost between the
query and the file. When it does not hold the report raises rather than printing, because a report
that publishes an imbalance as a figure has turned a detectable fault into a reported one.
"""

from __future__ import annotations

import csv
import io
from collections import defaultdict
from dataclasses import dataclass

from reporting.accounting import booked_balance
from reporting.ledger import AccountPosition, Position
from reporting.money import Money

__all__ = ["COLUMNS", "CurrencyTotal", "PositionReport", "render"]

COLUMNS = (
    "record",
    "accountRef",
    "customerRef",
    "accountType",
    "currency",
    "status",
    "debitMinor",
    "creditMinor",
    "bookedMinor",
    "bookedAmount",
    "movementCount",
)


@dataclass(frozen=True, slots=True)
class CurrencyTotal:
    """The control total for one currency."""

    currency: str
    accounts: int
    movements: int
    debit: Money
    credit: Money

    #: The net of every signed booked balance in this currency. Not zero in general - a liability
    #: and an asset both count positive when they hold what they should - so it is a figure to read
    #: rather than a check to pass. The check is ``debit == credit``.
    net: Money


@dataclass(frozen=True, slots=True)
class PositionReport:
    """One business date, one position, every account and the per-currency control totals."""

    business_date_ccyymmdd: str
    position: Position
    accounts: tuple[AccountPosition, ...]
    totals: tuple[CurrencyTotal, ...]

    @staticmethod
    def of(business_date, position: Position, accounts) -> PositionReport:
        ordered = tuple(sorted(accounts, key=lambda account: account.account_ref))

        by_currency: dict[str, list[AccountPosition]] = defaultdict(list)
        for account in ordered:
            by_currency[account.currency].append(account)

        totals: list[CurrencyTotal] = []
        for currency in sorted(by_currency):
            held = by_currency[currency]
            debit = Money(sum(account.debit_minor for account in held), currency)
            credit = Money(sum(account.credit_minor for account in held), currency)
            if debit != credit:
                raise ValueError(
                    f"{currency} does not balance: debits {debit.to_plain_string()}, "
                    f"credits {credit.to_plain_string()}. A posting is missing from this position, "
                    f"or an entry has a value date earlier than one of its accounts was opened."
                )
            net = Money(0, currency)
            for account in held:
                net = net + booked_balance(account)
            totals.append(
                CurrencyTotal(
                    currency=currency,
                    accounts=len(held),
                    movements=sum(account.movement_count for account in held),
                    debit=debit,
                    credit=credit,
                    net=net,
                )
            )

        return PositionReport(
            business_date_ccyymmdd=business_date.strftime("%Y%m%d"),
            position=position,
            accounts=ordered,
            totals=tuple(totals),
        )


def render(report: PositionReport) -> str:
    """The report as CSV: a header row, an ACCOUNT row per account, a TOTAL row per currency.

    ``lineterminator`` is set because :mod:`csv` defaults to CRLF, and a file whose bytes depend on
    which module wrote them is not a file anybody can diff against yesterday's.
    """
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(COLUMNS)

    for account in report.accounts:
        booked = booked_balance(account)
        writer.writerow(
            (
                "ACCOUNT",
                account.account_ref,
                account.customer_ref,
                account.account_type,
                account.currency,
                account.status,
                account.debit_minor,
                account.credit_minor,
                booked.minor,
                booked.to_plain_string(),
                account.movement_count,
            )
        )

    for total in report.totals:
        writer.writerow(
            (
                "TOTAL",
                "",
                "",
                "",
                total.currency,
                "",
                total.debit.minor,
                total.credit.minor,
                total.net.minor,
                total.net.to_plain_string(),
                total.movements,
            )
        )

    return buffer.getvalue()
