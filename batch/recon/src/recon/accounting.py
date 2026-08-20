"""Which way an account moves - the one accounting rule this tier needs.

An account's *normal balance* is the side on which it increases. A customer's current account is a
**liability** of the bank, so a credit increases it; the bank's cash is an **asset**, so a debit
does. This is the distinction that separates a real ledger from a ``balance`` column.

``AccountType.signedEffect`` in the ledger domain is the authority, and ``batch/reporting`` states
the rule a second time for its own reports. This is the third statement of it, and the duplication
is the point rather than the price: a reconciliation that asked the ledger which way its own figures
ran would be reconciling nothing, and one that borrowed the reporting job's answer would agree with
whatever the reporting job has wrong. Three independent implementations required to agree is a
control; one implementation consulted three times is a single point of failure with three names.

The tests pin the convention with hand-computed figures for every account type, which no shared code
path can fake.
"""

from __future__ import annotations

from types import MappingProxyType
from typing import Final

__all__ = ["NORMAL_BALANCE", "signed"]

NORMAL_BALANCE: Final = MappingProxyType(
    {
        "ASSET": "DEBIT",
        "LIABILITY": "CREDIT",
        "EQUITY": "CREDIT",
        "REVENUE": "CREDIT",
        "EXPENSE": "DEBIT",
    }
)


class UnknownAccountTypeError(KeyError):
    """An account type no accounting rule covers. Never guessed at."""


def signed(account_type: str, debit_minor: int, credit_minor: int) -> int:
    """The booked balance in minor units, signed by the account type's normal balance."""
    try:
        rises_on = NORMAL_BALANCE[account_type]
    except KeyError as problem:
        raise UnknownAccountTypeError(
            f"no normal balance for account type {account_type!r}; "
            f"the estate defines {sorted(NORMAL_BALANCE)}"
        ) from problem
    return debit_minor - credit_minor if rises_on == "DEBIT" else credit_minor - debit_minor
