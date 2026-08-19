"""Money: an integer count of minor units, and the ISO 4217 code that says how small they are.

The estate's oldest rule, restated here because this is a tier where it could be broken. It always
breaks the same way: somebody divides by 100 to show a figure on a report, and the divided value
flows back into a total that then reconciles to nothing. So this module does no true division, holds
no float, imports no Decimal, and a test parses it to prove all three rather than trusting the
sentence you are reading.

The scale table is transcribed from docs/architecture/canonical-data-model.md. A currency absent
from it is rejected rather than assumed to have two decimal places - which is the whole reason JPY
and BHD are carried at all.
"""

from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType
from typing import Final

__all__ = ["SCALES", "Money", "UnknownCurrencyError", "scale_of"]

SCALES: Final = MappingProxyType(
    {
        "PLN": 2,
        "EUR": 2,
        "USD": 2,
        "GBP": 2,
        "CHF": 2,
        # Zero-scale currencies. Present so a hard-coded 2 fails a test rather than passing quietly.
        "JPY": 0,
        "KRW": 0,
        # Three-scale currencies, carried for the same reason from the other side.
        "BHD": 3,
        "KWD": 3,
        "TND": 3,
    }
)


class UnknownCurrencyError(Exception):
    """A currency the scale table does not define.

    Guessing two decimal places would produce a report that is wrong by a factor of ten or a
    hundred and looks entirely ordinary, which is the worst kind of wrong a report can be.
    """

    def __init__(self, currency: str) -> None:
        self.currency = currency
        super().__init__(
            f"{currency!r} is not in the ISO 4217 scale table; add it to the canonical data model "
            f"first, then here"
        )


def scale_of(currency: str) -> int:
    """The number of decimal places ``currency`` has."""
    try:
        return SCALES[currency]
    except KeyError:
        raise UnknownCurrencyError(currency) from None


@dataclass(frozen=True, slots=True, order=True)
class Money:
    """An amount in minor units, with its currency.

    ``Money(123456789, "PLN")`` is 1 234 567.89 PLN. ``Money(100, "JPY")`` is 100 JPY. The integer
    means nothing without the code beside it, which is why they are one type.
    """

    minor: int
    currency: str

    def __post_init__(self) -> None:
        if not isinstance(self.minor, int) or isinstance(self.minor, bool):
            # bool is a subclass of int, and True + True == 2 is not an amount anybody meant.
            raise TypeError(f"minor units must be a whole number, got {self.minor!r}")
        scale_of(self.currency)

    @property
    def scale(self) -> int:
        return scale_of(self.currency)

    def __add__(self, other: Money) -> Money:
        self._require_same_currency(other)
        return Money(self.minor + other.minor, self.currency)

    def __sub__(self, other: Money) -> Money:
        self._require_same_currency(other)
        return Money(self.minor - other.minor, self.currency)

    def __neg__(self) -> Money:
        return Money(-self.minor, self.currency)

    def _require_same_currency(self, other: Money) -> None:
        if self.currency != other.currency:
            # No conversion exists anywhere in this estate, deliberately. An FX rate applied here
            # would be an unsourced, undated rate on a regulatory report.
            raise ValueError(
                f"cannot combine {self.currency} and {other.currency}: this estate holds no rates"
            )

    def to_plain_string(self) -> str:
        """The amount with its decimal point placed, for a human reading a report.

        Built by slicing digits, never by dividing. ``-5 PLN`` is ``-0.05`` and not ``-0.5``, which
        is the mistake a naive sign-then-pad implementation makes on every amount below one unit.
        """
        digits = str(abs(self.minor))
        sign = "-" if self.minor < 0 else ""
        if self.scale == 0:
            return f"{sign}{digits}"
        padded = digits.rjust(self.scale + 1, "0")
        return f"{sign}{padded[: -self.scale]}.{padded[-self.scale :]}"

    def to_fixed(self, width: int) -> tuple[str, str]:
        """The sign character and the zero-padded digits for a fixed-width field.

        Returned as a pair because the regulatory extract keeps them in separate columns: the digits
        are then always exactly ``width`` bytes and a reader can slice the field without parsing it.

        An amount too wide for the field raises rather than truncating. A truncated amount is not a
        smaller amount, it is a different one, and on a control total it agrees with nothing while
        looking entirely plausible.
        """
        digits = str(abs(self.minor))
        if len(digits) > width:
            raise OverflowError(
                f"{self.to_plain_string()} {self.currency} needs {len(digits)} digits, "
                f"the field holds {width}"
            )
        return ("-" if self.minor < 0 else "+", digits.rjust(width, "0"))
