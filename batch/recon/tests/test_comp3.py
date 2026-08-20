"""The decoder, held to bytes it did not produce.

Two independent sources, because agreeing with itself proves nothing. The worked examples in
``docs/architecture/canonical-data-model.md`` appear here as literals - the canonical model is the
arbiter for what a packed amount means across this estate - and the balances come out of
``ACCTMAST.DAT``, which stratum 0's own generator wrote.

There are now three implementations of COMP-3 in this repository: ``mainframe/data/comp3.py``,
``Comp3.java`` in the integration tier, and this one. That is deliberate. A decoder that imported
one of the others would inherit whatever that one has wrong, and a reconciliation is the last place
in the estate where agreement by construction is acceptable.
"""

from __future__ import annotations

import pytest

from recon.comp3 import DecodeError, decode

# The canonical data model's worked examples, section 2. Transcribed as literals on purpose.
PLN_1_234_567_89 = bytes([0x00, 0x00, 0x00, 0x12, 0x34, 0x56, 0x78, 0x9C])
PLN_MINUS_1_234_567_89 = bytes([0x00, 0x00, 0x00, 0x12, 0x34, 0x56, 0x78, 0x9D])
ZERO = bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C])
MAXIMUM = bytes([0x99, 0x99, 0x99, 0x99, 0x99, 0x99, 0x99, 0x9C])


def test_a_positive_amount() -> None:
    assert decode(PLN_1_234_567_89) == 123_456_789


def test_a_negative_amount_differs_only_in_the_sign_nibble() -> None:
    assert decode(PLN_MINUS_1_234_567_89) == -123_456_789
    assert PLN_1_234_567_89[:-1] == PLN_MINUS_1_234_567_89[:-1]


def test_zero() -> None:
    assert decode(ZERO) == 0


def test_the_maximum_representable_amount() -> None:
    assert decode(MAXIMUM) == 999_999_999_999_999


def test_an_unsigned_field_reads_as_positive() -> None:
    """0x0F is what a field declared without S writes. Nothing here emits it; this reads it."""
    assert decode(bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1F])) == 1


def test_a_negative_zero_still_reads_as_zero() -> None:
    assert decode(bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0D])) == 0


def test_the_wrong_number_of_bytes_is_refused() -> None:
    with pytest.raises(DecodeError, match="8 bytes"):
        decode(bytes([0x00, 0x0C]))


def test_an_unrecognised_sign_nibble_is_refused() -> None:
    with pytest.raises(DecodeError, match="sign nibble"):
        decode(bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0A]))


def test_a_digit_nibble_above_nine_is_refused() -> None:
    """0xA-0xF in a digit position is corruption, and int() on it would raise something opaque."""
    with pytest.raises(DecodeError, match="digit nibble"):
        decode(bytes([0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xAB, 0x0C]))
