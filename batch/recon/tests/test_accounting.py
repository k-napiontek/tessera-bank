"""The normal-balance rule, pinned by hand-computed figures for every account type.

No database and no shared code path. This is the test that would catch the sign convention being
copied wrongly out of the ledger domain, which is the one mistake that would make every balance in
the break report plausible and inverted.
"""

from __future__ import annotations

import pytest

from recon.accounting import NORMAL_BALANCE, UnknownAccountTypeError, signed


@pytest.mark.parametrize(
    ("account_type", "expected"),
    [
        ("ASSET", 300),  # rises on the debit: 1000 debit - 700 credit
        ("EXPENSE", 300),
        ("LIABILITY", -300),  # rises on the credit: 700 credit - 1000 debit
        ("EQUITY", -300),
        ("REVENUE", -300),
    ],
)
def test_the_sign_convention_for_every_type(account_type: str, expected: int) -> None:
    assert signed(account_type, debit_minor=1000, credit_minor=700) == expected


def test_every_type_the_estate_defines_is_covered() -> None:
    assert set(NORMAL_BALANCE) == {"ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE"}


def test_an_account_with_no_postings_is_flat() -> None:
    assert signed("LIABILITY", 0, 0) == 0


def test_an_unknown_account_type_is_refused_rather_than_assumed() -> None:
    """Defaulting to one side would put a wrong balance in a break report and call it a break."""
    with pytest.raises(UnknownAccountTypeError, match="CONTINGENCY"):
        signed("CONTINGENCY", 100, 0)
