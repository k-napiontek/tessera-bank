"""The one accounting rule this tier needs: which way an account moves.

An account's *normal balance* is the side on which it increases. A customer's current account is a
**liability** of the bank - the bank owes that money - so a credit increases it. The bank's cash and
its central-bank reserves are **assets**, so a debit increases them. This is the distinction that
separates a real ledger from a ``balance`` column, and it is stated here rather than inside the SQL
so that it can be tested without a database and changed in exactly one place.

It agrees with ``AccountType.signedEffect`` in the ledger domain, which is the authority. Two
statements of one rule is one too many; what makes this acceptable is that a report is a different
program from the ledger, and a report that asked the ledger which way its own figures ran would be
reconciling nothing.
"""

from __future__ import annotations

from types import MappingProxyType
from typing import TYPE_CHECKING, Final

from reporting.money import Money

if TYPE_CHECKING:  # pragma: no cover - import cycle avoided at runtime, kept for readers
    from reporting.ledger import AccountPosition

__all__ = ["NORMAL_BALANCE", "booked_balance"]

NORMAL_BALANCE: Final = MappingProxyType(
    {
        "ASSET": "DEBIT",
        "LIABILITY": "CREDIT",
        "EQUITY": "CREDIT",
        "REVENUE": "CREDIT",
        "EXPENSE": "DEBIT",
    }
)


def booked_balance(account: AccountPosition) -> Money:
    """The account's booked balance, signed by its own normal balance.

    Positive means the account holds what it is supposed to hold: a liability with a credit balance
    is money the bank owes a customer, and an asset with a debit balance is money the bank has.

    An account type the table does not know raises rather than defaulting to one side. Guessing
    would put a figure on a regulatory report with its sign chosen at random.
    """
    normal = NORMAL_BALANCE[account.account_type]
    if normal == "DEBIT":
        return Money(account.debit_minor - account.credit_minor, account.currency)
    return Money(account.credit_minor - account.debit_minor, account.currency)
