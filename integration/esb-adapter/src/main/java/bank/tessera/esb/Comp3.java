package bank.tessera.esb;

/**
 * COMP-3 packed decimal, as the mainframe writes it.
 *
 * <p>The estate's money field is {@code PIC S9(13)V99 COMP-3}: fifteen digits in eight bytes,
 * {@code ceil((15 + 1) / 2)}. Packed decimal stores one decimal digit per 4-bit nibble, two per
 * byte, high nibble first, with the <strong>final nibble holding the sign</strong>. {@code V} is an
 * implied decimal point that occupies nothing, so the stored digits <em>are</em> {@code amountMinor}
 * - but only for a currency whose ISO 4217 scale is 2, which is what
 * {@link CurrencyScales#requireRepresentableOnTheMainframe(String)} is for.
 *
 * <p>Packed decimal has <strong>no endianness</strong>. It is a digit string, not an integer, so
 * this encoder and a COBOL runtime produce identical bytes with no byte-order handling anywhere -
 * which is the whole reason {@code Comp3Test} can compare byte for byte rather than value for value.
 *
 * <p>Two things here are easy to get wrong and produce a file every reader accepts:
 *
 * <ul>
 * <li><strong>Zero is positive.</strong> A {@code -0} would break byte-for-byte comparison against
 * the mainframe's own fixtures and manufacture a reconciliation break in WP-16 that no posting
 * caused.</li>
 * <li><strong>The last byte is not sign-only.</strong> It carries the fifteenth digit in its high
 * nibble: {@code +1} is {@code ...0x1C}, not {@code ...0x0C}.</li>
 * </ul>
 *
 * <p>{@code 0x0F} - unsigned - is what a field declared without {@code S} writes. It is accepted on
 * read elsewhere in the estate and is never written here; an encoder that emitted it would produce a
 * file the mainframe reads as unsigned, silently.
 *
 * <p>The reference implementation is {@code mainframe/data/comp3.py} and the arbiter is the worked
 * examples in {@code docs/architecture/canonical-data-model.md} section 2.
 */
public final class Comp3 {

    /** Digits in {@code PIC S9(13)V99}: thirteen before the implied point, two after. */
    public static final int DIGITS = 15;

    /** Bytes occupied: {@code ceil((15 + 1) / 2)}. */
    public static final int SIZE = 8;

    /** The largest amount the picture clause can hold, in minor units. */
    public static final long MAX = 999_999_999_999_999L;

    /** The only positive sign this estate writes. Zero uses it too. */
    private static final int POSITIVE = 0x0C;

    /** The only negative sign this estate writes. */
    private static final int NEGATIVE = 0x0D;

    private Comp3() {
    }

    /**
     * Pack an amount in minor units into eight bytes of COMP-3.
     *
     * @throws TransferHandlingException permanently, at stage {@code ENCODE}, when the amount does
     *     not fit. Refusal rather than truncation: a silently narrowed amount is the defect that
     *     prints as an ordinary-looking number, and re-presenting the same message would produce the
     *     same overflow, so retrying it would achieve nothing.
     */
    public static byte[] encode(long amountMinor) {
        if (amountMinor > MAX || amountMinor < -MAX) {
            throw TransferHandlingException.permanent(FailureStage.ENCODE,
                    "amount " + amountMinor + " minor units does not fit in PIC S9(13)V99 COMP-3;"
                            + " the range is -" + MAX + " to " + MAX);
        }

        // Left-zero-padded to the full fifteen digits, then read one nibble at a time. Math.abs is
        // safe here only because the range check above already excluded Long.MIN_VALUE.
        char[] digits = pad(Long.toString(Math.abs(amountMinor)));

        byte[] packed = new byte[SIZE];
        for (int i = 0; i < SIZE - 1; i++) {
            packed[i] = (byte) ((value(digits[i * 2]) << 4) | value(digits[i * 2 + 1]));
        }
        // The last byte pairs the fifteenth digit with the sign, never the sign alone.
        int sign = amountMinor < 0 ? NEGATIVE : POSITIVE;
        packed[SIZE - 1] = (byte) ((value(digits[DIGITS - 1]) << 4) | sign);
        return packed;
    }

    private static char[] pad(String digits) {
        char[] padded = new char[DIGITS];
        int leading = DIGITS - digits.length();
        for (int i = 0; i < leading; i++) {
            padded[i] = '0';
        }
        digits.getChars(0, digits.length(), padded, leading);
        return padded;
    }

    private static int value(char digit) {
        return digit - '0';
    }
}
