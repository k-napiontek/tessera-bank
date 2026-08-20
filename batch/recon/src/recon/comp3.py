"""COMP-3 packed decimal, decoded - the mainframe's money as an integer.

The estate's money field is ``PIC S9(13)V99 COMP-3``: fifteen digits in eight bytes, one decimal
digit per 4-bit nibble, high nibble first, the final nibble holding the sign. ``V`` is an implied
decimal point that occupies nothing, so the digits *are* minor units for a currency of scale 2 -
which is the only scale stratum 0 can hold, and the integration tier refuses anything else before
it gets here.

Packed decimal has no endianness. It is a digit string, not an integer, so a Python decoder and a
COBOL runtime see the same bytes the same way with no byte-order handling anywhere.

**Everything malformed is refused rather than interpreted.** A reconciliation exists to notice that
two systems disagree; one that quietly turned a corrupt field into a plausible number would report
drift the master does not actually contain, and an operator would go looking for a movement that
was never made. ``int()`` over a nibble above nine would raise something opaque from deep inside the
standard library, so the digits are checked here where the message can name the field.
"""

from __future__ import annotations

from typing import Final

__all__ = ["DIGITS", "SIZE", "DecodeError", "decode"]

#: Digits in ``PIC S9(13)V99``: thirteen before the implied point, two after.
DIGITS: Final = 15

#: Bytes occupied: ceil((15 + 1) / 2).
SIZE: Final = 8

_POSITIVE: Final = 0x0C
_NEGATIVE: Final = 0x0D
#: What a field declared without ``S`` writes. Never emitted by this estate, accepted on read.
_UNSIGNED: Final = 0x0F


class DecodeError(ValueError):
    """A field that is not COMP-3. Never guessed at, never partially read."""


def decode(raw: bytes) -> int:
    """Unpack eight bytes of ``PIC S9(13)V99 COMP-3`` into minor units."""
    if len(raw) != SIZE:
        raise DecodeError(f"expected {SIZE} bytes of COMP-3, got {len(raw)}: {_hex(raw)}")

    nibbles = []
    for byte in raw:
        nibbles.append(byte >> 4)
        nibbles.append(byte & 0x0F)

    sign = nibbles.pop()
    if sign not in (_POSITIVE, _NEGATIVE, _UNSIGNED):
        raise DecodeError(f"unrecognised sign nibble 0x{sign:X}, expected C, D or F: {_hex(raw)}")

    for position, nibble in enumerate(nibbles):
        if nibble > 9:
            raise DecodeError(
                f"digit nibble 0x{nibble:X} at position {position} is not a decimal digit: "
                f"{_hex(raw)}"
            )

    value = 0
    for nibble in nibbles:
        value = value * 10 + nibble
    # -0 is not a number this estate writes and not one it should return: a negative zero compared
    # against a positive zero is equal in Python, but it would print as "-0" in a break report.
    return -value if sign == _NEGATIVE and value else value


def _hex(raw: bytes) -> str:
    """A hex dump in the form the canonical data model prints, so an error can be compared to it."""
    return " ".join(f"0x{byte:02X}" for byte in raw)
