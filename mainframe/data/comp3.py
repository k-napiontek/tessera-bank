#!/usr/bin/env python3
"""COMP-3 packed decimal, as the mainframe writes it.

Packed decimal stores one decimal digit per 4-bit nibble, two digits per byte, with the final nibble
holding the sign. A signed field of n digits therefore occupies ceil((n + 1) / 2) bytes.

The estate's money field is ``PIC S9(13)V99 COMP-3``: fifteen digits, eight bytes. ``V`` is an implied
decimal point and occupies nothing, so the stored digits *are* the amount in minor units - for a
currency whose ISO 4217 scale is 2, which is the only kind stratum 0 can represent.

Packed decimal has **no endianness**. It is a digit string, not an integer, which is why a Java
encoder and a COBOL runtime produce identical bytes with no byte-order handling anywhere - and why
WP-11 can compare byte for byte rather than value for value.

See docs/architecture/canonical-data-model.md section 2 for the worked examples this implements.
"""

DIGITS = 15
"""Digits in PIC S9(13)V99: thirteen before the implied point, two after."""

SIZE = (DIGITS + 1) // 2 + (DIGITS + 1) % 2
"""Bytes occupied: ceil((15 + 1) / 2) = 8."""

POSITIVE = 0x0C
"""The only positive sign this estate writes. Zero uses it too."""

NEGATIVE = 0x0D
"""The only negative sign this estate writes."""

UNSIGNED = 0x0F
"""Written by fields declared without S. Accepted on read, never written."""

MAX = 10**DIGITS - 1


def encode_comp3(amount_minor: int, digits: int = DIGITS) -> bytes:
    """Pack ``amount_minor`` into COMP-3.

    Zero is always positive. A negative zero would break byte-for-byte comparison against the Java
    encoder in WP-11 and produce a reconciliation break in WP-16 that no posting caused.
    """
    limit = 10**digits - 1
    if not -limit <= amount_minor <= limit:
        raise ValueError(
            f"{amount_minor} does not fit in PIC S9({digits - 2})V99 COMP-3;"
            f" the range is -{limit} to {limit} minor units"
        )

    sign = NEGATIVE if amount_minor < 0 else POSITIVE
    packed = str(abs(amount_minor)).zfill(digits)

    nibbles = [int(character) for character in packed] + [sign]
    return bytes(
        (nibbles[i] << 4) | nibbles[i + 1] for i in range(0, len(nibbles), 2)
    )


def decode_comp3(raw: bytes, digits: int = DIGITS) -> int:
    """Unpack COMP-3 back to minor units.

    Accepts ``0x0F`` as positive because unsigned fields elsewhere in the estate write it, even
    though nothing here ever does.
    """
    expected = (digits + 1) // 2 + (digits + 1) % 2
    if len(raw) != expected:
        raise ValueError(f"expected {expected} bytes for {digits} digits, got {len(raw)}")

    nibbles = []
    for byte in raw:
        nibbles.append(byte >> 4)
        nibbles.append(byte & 0x0F)

    sign = nibbles.pop()
    if sign not in (POSITIVE, NEGATIVE, UNSIGNED):
        raise ValueError(f"unrecognised sign nibble 0x{sign:X}; expected C, D or F")

    value = int("".join(str(nibble) for nibble in nibbles))
    return -value if sign == NEGATIVE else value


def to_hex(raw: bytes) -> str:
    """A hex dump in the form the canonical data model prints, for error messages."""
    return " ".join(f"0x{byte:02X}" for byte in raw)
