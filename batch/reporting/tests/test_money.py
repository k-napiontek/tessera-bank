"""Money is minor units and a currency, and it never becomes a float.

The estate's oldest rule: an amount is an integer count of the smallest unit the currency has, plus
the ISO 4217 code that says how small that is. Every tier restates it because every tier is where it
could be broken - and the way it gets broken is always the same, a division introduced to "display"
a value that then flows back into a total.
"""

from __future__ import annotations

import ast
import pathlib

import pytest

from reporting.money import SCALES, Money, UnknownCurrencyError, scale_of


def test_the_scale_table_is_the_canonical_one() -> None:
    # Carried specifically so a hard-coded 2 fails rather than passes quietly.
    assert scale_of("PLN") == 2
    assert scale_of("JPY") == 0
    assert scale_of("BHD") == 3
    assert set(SCALES) == {"PLN", "EUR", "USD", "GBP", "CHF", "JPY", "KRW", "BHD", "KWD", "TND"}


def test_a_currency_absent_from_the_table_is_rejected_not_guessed() -> None:
    with pytest.raises(UnknownCurrencyError):
        scale_of("XYZ")


def test_money_carries_minor_units_and_a_currency() -> None:
    amount = Money(123_456_789, "PLN")

    assert amount.minor == 123_456_789
    assert amount.currency == "PLN"
    assert amount.scale == 2


def test_addition_requires_the_same_currency() -> None:
    assert Money(100, "PLN") + Money(23, "PLN") == Money(123, "PLN")

    with pytest.raises(ValueError, match=r"PLN.*EUR|EUR.*PLN"):
        Money(100, "PLN") + Money(1, "EUR")


def test_the_plain_string_places_the_point_from_the_scale() -> None:
    assert Money(123_456_789, "PLN").to_plain_string() == "1234567.89"
    assert Money(100, "JPY").to_plain_string() == "100"
    assert Money(100, "BHD").to_plain_string() == "0.100"
    assert Money(-5, "PLN").to_plain_string() == "-0.05"
    assert Money(0, "PLN").to_plain_string() == "0.00"


def test_a_fixed_width_field_is_a_sign_and_zero_padded_digits() -> None:
    assert Money(123_456_789, "PLN").to_fixed(15) == ("+", "000000123456789")
    assert Money(-1, "PLN").to_fixed(15) == ("-", "000000000000001")
    assert Money(0, "PLN").to_fixed(15) == ("+", "000000000000000")


def test_an_amount_too_wide_for_its_field_fails_rather_than_truncates() -> None:
    # A truncated control total agrees with nothing and looks entirely plausible.
    with pytest.raises(OverflowError):
        Money(1_000_000_000_000_000, "PLN").to_fixed(15)


def test_the_module_contains_no_floating_point_arithmetic() -> None:
    # The ledger scans its own sources for BigDecimal for this reason. Here the words differ and the
    # failure is identical: a division introduced to display a value, flowing back into a total that
    # no longer reconciles.
    #
    # Parsed rather than grepped. A grep over the source text is fooled by its own documentation -
    # this comment would trip it - and a check that cannot survive being explained is not a check.
    source = pathlib.Path(__file__).resolve().parents[1] / "src" / "reporting" / "money.py"
    tree = ast.parse(source.read_text(encoding="utf-8"))

    divisions = [node for node in ast.walk(tree) if isinstance(node, ast.BinOp | ast.AugAssign)]
    assert not [node for node in divisions if isinstance(node.op, ast.Div)], (
        "true division in money.py: the result is a float"
    )

    called = {
        node.func.id
        for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
    }
    named = {node.id for node in ast.walk(tree) if isinstance(node, ast.Name)}
    for forbidden in ("float", "Decimal", "round"):
        assert forbidden not in called | named, f"{forbidden} has no business in money.py"
