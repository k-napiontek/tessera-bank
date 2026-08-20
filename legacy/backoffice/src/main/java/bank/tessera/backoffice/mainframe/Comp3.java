package bank.tessera.backoffice.mainframe;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * COMP-3 packed decimal, decoded, so an operator does not read packed bytes by eye.
 *
 * <p>{@code PIC S9(13)V99 COMP-3}: fifteen digits in eight bytes, one decimal digit per nibble,
 * high nibble first, the last nibble the sign. {@code 0x0C} is positive, {@code 0x0D} negative,
 * {@code 0x0F} unsigned and accepted on read though nothing in this estate writes it.
 *
 * <p>This is the <strong>fourth</strong> implementation of COMP-3 in this repository, after
 * {@code mainframe/data/comp3.py}, {@code Comp3.java} at stratum 2 and {@code recon/comp3.py}. That
 * is deliberate, and it is the same reasoning the reconciliation records: a decoder that borrowed
 * another tier's would inherit whatever that tier has wrong, and this one reads a file the others
 * never touch. It is held to bytes it did not produce - the canonical data model's worked examples,
 * as literals.
 *
 * <p><strong>Money is never a floating-point number.</strong> The value comes back as minor units
 * and is rendered through {@link BigDecimal}, never {@code double}. A screen that displayed a
 * balance through a double would be wrong rarely enough to reach production.
 */
public final class Comp3 {

    /** Bytes occupied by {@code PIC S9(13)V99 COMP-3}. */
    public static final int SIZE = 8;

    private static final int POSITIVE = 0x0C;
    private static final int NEGATIVE = 0x0D;
    private static final int UNSIGNED = 0x0F;

    private Comp3() {
    }

    /** Unpack eight bytes into minor units. */
    public static long decode(byte[] raw, int offset) {
        if (raw == null || offset < 0 || offset + SIZE > raw.length) {
            throw new IllegalArgumentException(
                    "no " + SIZE + " bytes of COMP-3 at offset " + offset);
        }

        StringBuilder digits = new StringBuilder(15);
        int sign = 0;
        for (int i = 0; i < SIZE; i++) {
            int b = raw[offset + i] & 0xFF;
            int high = b >> 4;
            int low = b & 0x0F;
            appendDigit(digits, high, raw, offset);
            if (i == SIZE - 1) {
                sign = low;
            } else {
                appendDigit(digits, low, raw, offset);
            }
        }

        if (sign != POSITIVE && sign != NEGATIVE && sign != UNSIGNED) {
            throw new IllegalArgumentException("unrecognised sign nibble 0x"
                    + Integer.toHexString(sign).toUpperCase() + " in " + hex(raw, offset));
        }

        BigInteger value = new BigInteger(digits.toString());
        if (sign == NEGATIVE) {
            value = value.negate();
        }
        return value.longValue();
    }

    /** Minor units as a scale-2 decimal, for display. Never a double. */
    public static BigDecimal toAmount(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, 2);
    }

    private static void appendDigit(StringBuilder digits, int nibble, byte[] raw, int offset) {
        if (nibble > 9) {
            throw new IllegalArgumentException("digit nibble 0x"
                    + Integer.toHexString(nibble).toUpperCase() + " is not a decimal digit in "
                    + hex(raw, offset));
        }
        digits.append((char) ('0' + nibble));
    }

    private static String hex(byte[] raw, int offset) {
        StringBuilder text = new StringBuilder();
        for (int i = offset; i < offset + SIZE && i < raw.length; i++) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(String.format("0x%02X", Byte.valueOf(raw[i])));
        }
        return text.toString();
    }
}
