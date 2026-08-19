"""The movement summary: every posting of one business date, with control totals per currency.

Where the position report answers "where does everything stand", this answers "what moved today" -
and it is the report an operator reconciles against the mainframe's own end-of-day figures, which is
why its control totals are counts and sums of each side rather than a net.

**Debits equal credits, per currency.** For any set of complete entries that is not an observation,
it is the definition of double entry, so a currency where they disagree is a currency where a
posting went missing between the query and the file. The report raises rather than printing: an
imbalance published as a figure has become a reported number instead of a detected fault.
"""

from __future__ import annotations

import csv
import io
from collections import defaultdict
from dataclasses import dataclass

from reporting.ledger import Movement, Position
from reporting.money import Money

__all__ = ["COLUMNS", "MovementReport", "SideTotal", "render"]

COLUMNS = (
    "record",
    "entryRef",
    "leg",
    "accountRef",
    "accountType",
    "direction",
    "currency",
    "amountMinor",
    "amount",
    "count",
)


@dataclass(frozen=True, slots=True)
class SideTotal:
    """One currency's debits and credits for the day."""

    currency: str
    debit: Money
    credit: Money
    debit_count: int
    credit_count: int


@dataclass(frozen=True, slots=True)
class MovementReport:
    """One business date, one position, every posting and the per-currency control totals."""

    business_date_ccyymmdd: str
    position: Position
    movements: tuple[Movement, ...]
    totals: tuple[SideTotal, ...]

    @staticmethod
    def of(business_date, position: Position, movements) -> MovementReport:
        # The reader already returns these ordered by (entry_ref, seq), which is total because
        # posting_seq_uq makes the pair unique. Re-sorting on the same key costs nothing and means
        # the report does not depend on a caller having preserved it.
        ordered = tuple(sorted(movements, key=lambda m: (m.entry_ref, m.seq)))

        by_currency: dict[str, list[Movement]] = defaultdict(list)
        for movement in ordered:
            by_currency[movement.currency].append(movement)

        totals: list[SideTotal] = []
        for currency in sorted(by_currency):
            legs = by_currency[currency]
            debits = [leg for leg in legs if leg.direction == "DEBIT"]
            credits = [leg for leg in legs if leg.direction == "CREDIT"]
            debit = Money(sum(leg.amount_minor for leg in debits), currency)
            credit = Money(sum(leg.amount_minor for leg in credits), currency)
            if debit != credit:
                raise ValueError(
                    f"{currency} does not balance: debits {debit.to_plain_string()}, "
                    f"credits {credit.to_plain_string()}. Every entry has both legs, so this "
                    f"position is missing a posting."
                )
            totals.append(
                SideTotal(
                    currency=currency,
                    debit=debit,
                    credit=credit,
                    debit_count=len(debits),
                    credit_count=len(credits),
                )
            )

        return MovementReport(
            business_date_ccyymmdd=business_date.strftime("%Y%m%d"),
            position=position,
            movements=ordered,
            totals=tuple(totals),
        )


def render(report: MovementReport) -> str:
    """The report as CSV: a MOVEMENT row per posting, then a TOTAL row per currency and side.

    Each side gets its own row rather than sharing one, so a reader comparing this file against the
    mainframe's report is comparing like with like - EODREPT counts and totals debits and credits
    separately too, because that is how a difference tells you which side it is on.
    """
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(COLUMNS)

    for movement in report.movements:
        amount = Money(movement.amount_minor, movement.currency)
        writer.writerow(
            (
                "MOVEMENT",
                movement.entry_ref,
                movement.seq,
                movement.account_ref,
                movement.account_type,
                movement.direction,
                movement.currency,
                amount.minor,
                amount.to_plain_string(),
                "",
            )
        )

    for total in report.totals:
        for direction, amount, count in (
            ("DEBIT", total.debit, total.debit_count),
            ("CREDIT", total.credit, total.credit_count),
        ):
            writer.writerow(
                (
                    "TOTAL",
                    "",
                    "",
                    "",
                    "",
                    direction,
                    total.currency,
                    amount.minor,
                    amount.to_plain_string(),
                    count,
                )
            )

    return buffer.getvalue()
