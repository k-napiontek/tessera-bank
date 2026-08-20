package bank.tessera.esb;

import java.nio.charset.Charset;

/**
 * One leg of a double-entry posting, as 120 bytes of {@code MOVEREC}.
 *
 * <p>The layout is {@code contracts/copybook/MOVEREC.CPY} and the offsets below are the ones
 * {@code contracts/check-copybook-offsets.py} computes from it. {@code MovementRecordTest} asserts
 * them against that script rather than against a second hand count, because two hand counts that
 * agree prove nothing.
 *
 * <pre>
 *   MOV-TRANSFER-REF     1-20    PIC X(20)
 *   MOV-LEG-NO          21-22    PIC 9(02)
 *   MOV-ACCT-REF        23-38    PIC X(16)
 *   MOV-DIRECTION       39-39    PIC X(01)     'D' debit, 'C' credit
 *   MOV-CURRENCY        40-42    PIC X(03)
 *   MOV-AMOUNT          43-50    PIC S9(13)V99 COMP-3   always positive
 *   MOV-VALUE-DATE      51-58    PIC 9(08)
 *   MOV-POSTED-TS       59-72    PIC 9(14)
 *   MOV-REFERENCE       73-107   PIC X(35)
 *   FILLER             108-120   PIC X(13)
 * </pre>
 *
 * <p>Two padding rules, and they are opposite ways round. {@code PIC X(n)} is left-justified and
 * padded on the right with spaces; {@code PIC 9(n)} is right-justified and padded on the left with
 * {@code '0'}. Never {@code 0x00}: {@code check-records.py} fails on a null in a display field, and
 * a 1995 reader would print it.
 *
 * <p><strong>The amount is always positive and {@code MOV-DIRECTION} carries the sign</strong>, as
 * the copybook's own header says. An encoder that signs the amount instead produces a file
 * {@code ACCTPOST} rejects in full as {@code R006 AMOUNT NOT POSITIVE} - a run that looks like bad
 * data rather than like a bug here.
 *
 * <p>Nothing in this class sorts or orders anything, and that is deliberate. The copybook says the
 * file is ascending by {@code MOV-ACCT-REF}, but the cycle's {@code STEP010} is what puts it in that
 * order - which is the entire reason that step exists. This tier writes in the order events arrive.
 */
public final class MovementRecord {

    /** Bytes per record, fixed. */
    public static final int LENGTH = 120;

    /** Zero-based offset of the transfer reference, and its width - the file's de-duplication key. */
    public static final int TRANSFER_REF_AT = 0;
    public static final int TRANSFER_REF_SIZE = 20;

    private static final int LEG_NO_AT = 20;
    private static final int LEG_NO_SIZE = 2;
    private static final int ACCT_REF_AT = 22;
    private static final int ACCT_REF_SIZE = 16;
    private static final int DIRECTION_AT = 38;
    private static final int DIRECTION_SIZE = 1;
    private static final int CURRENCY_AT = 39;
    private static final int CURRENCY_SIZE = 3;
    private static final int AMOUNT_AT = 42;
    private static final int VALUE_DATE_AT = 50;
    private static final int VALUE_DATE_SIZE = 8;
    private static final int POSTED_TS_AT = 58;
    private static final int POSTED_TS_SIZE = 14;
    private static final int REFERENCE_AT = 72;
    private static final int REFERENCE_SIZE = 35;
    private static final int FILLER_AT = 107;
    private static final int FILLER_SIZE = 13;

    /** One byte per character, as PIC X. The mainframe's own encoding is EBCDIC; the bridge is not. */
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private MovementRecord() {
    }

    /**
     * Build one movement record.
     *
     * @param direction the canonical {@code DirectionType}, {@code DEBIT} or {@code CREDIT}
     * @param amountMinor the amount in minor units, positive - the sign belongs to the direction
     * @param reference the remittance reference, or null when the transfer carried none
     * @throws TransferHandlingException permanently, at stage {@code ENCODE}, when the movement
     *     cannot be represented at stratum 0 at all
     */
    public static byte[] of(String transferRef, int legNo, String accountRef, String direction,
            String currency, long amountMinor, String valueDate, String postedTs, String reference) {

        // The same rule the bridge applied before the SOAP call, asserted again where the bytes are
        // actually produced. Defence in depth: a 1995 core does not trust its feeds, and neither
        // does the tier that writes to it.
        CurrencyScales.requireRepresentableOnTheMainframe(currency);

        if (amountMinor < 0) {
            throw TransferHandlingException.permanent(FailureStage.ENCODE,
                    "MOV-AMOUNT is always positive and MOV-DIRECTION carries the sign, but leg "
                            + legNo + " arrived with " + amountMinor + " minor units");
        }

        byte[] record = new byte[LENGTH];
        // FILLER and every display field start life blank, so a field written short is space-padded
        // by construction rather than by remembering to.
        java.util.Arrays.fill(record, (byte) ' ');

        display(record, "MOV-TRANSFER-REF", TRANSFER_REF_AT, TRANSFER_REF_SIZE, transferRef);
        digits(record, "MOV-LEG-NO", LEG_NO_AT, LEG_NO_SIZE, Integer.toString(legNo));
        display(record, "MOV-ACCT-REF", ACCT_REF_AT, ACCT_REF_SIZE, accountRef);
        display(record, "MOV-DIRECTION", DIRECTION_AT, DIRECTION_SIZE, directionCode(direction));
        display(record, "MOV-CURRENCY", CURRENCY_AT, CURRENCY_SIZE, currency);
        System.arraycopy(Comp3.encode(amountMinor), 0, record, AMOUNT_AT, Comp3.SIZE);
        digits(record, "MOV-VALUE-DATE", VALUE_DATE_AT, VALUE_DATE_SIZE, valueDate);
        digits(record, "MOV-POSTED-TS", POSTED_TS_AT, POSTED_TS_SIZE, postedTs);
        display(record, "MOV-REFERENCE", REFERENCE_AT, REFERENCE_SIZE,
                reference == null ? "" : reference);
        // FILLER 108-120 was blanked with everything else and stays that way.
        return record;
    }

    /** The transfer reference of a record already in the file - the file's own de-duplication key. */
    public static String transferRefOf(byte[] record, int offset) {
        return new String(record, offset + TRANSFER_REF_AT, TRANSFER_REF_SIZE, ASCII);
    }

    /** {@code D} or {@code C}: one byte, because 1995 had no room for a word. */
    private static String directionCode(String direction) {
        if ("DEBIT".equals(direction)) {
            return "D";
        }
        if ("CREDIT".equals(direction)) {
            return "C";
        }
        throw TransferHandlingException.permanent(FailureStage.ENCODE,
                "direction " + direction + " is neither DEBIT nor CREDIT, and MOV-DIRECTION has"
                        + " exactly two values");
    }

    /** PIC X(n): left-justified, the rest already blank. */
    private static void display(byte[] record, String field, int at, int size, String value) {
        byte[] raw = oneByteEach(field, size, value);
        System.arraycopy(raw, 0, record, at, raw.length);
    }

    /** PIC 9(n): right-justified, zero-padded on the left. */
    private static void digits(byte[] record, String field, int at, int size, String value) {
        byte[] raw = oneByteEach(field, size, value);
        int leading = size - raw.length;
        for (int i = 0; i < leading; i++) {
            record[at + i] = '0';
        }
        System.arraycopy(raw, 0, record, at + leading, raw.length);
    }

    /**
     * The bytes of a value, refused rather than mangled when the field cannot hold them.
     *
     * <p>Both refusals are permanent and both are the same kind of mistake caught in two ways. A
     * value too long would be truncated, and a truncated remittance reference is the paying
     * customer's own words silently cut in half. A character outside ASCII has no single-byte
     * representation, so a lossy encode turns it into {@code ?} and a multi-byte one shifts every
     * field after it - both produce a file that reads perfectly and says the wrong thing.
     *
     * <p>Neither should be reachable: the canonical schema caps the reference at 35 and the
     * transformer validates against it before anything gets here. Arriving anyway means the schema
     * and this encoder disagree, which is worth a dead letter naming the field.
     */
    private static byte[] oneByteEach(String field, int size, String value) {
        byte[] raw = value.getBytes(ASCII);

        if (raw.length > size) {
            throw TransferHandlingException.permanent(FailureStage.ENCODE,
                    field + " holds " + size + " bytes and the value needs " + raw.length
                            + "; truncating it would silently change what the record says");
        }
        if (!new String(raw, ASCII).equals(value)) {
            throw TransferHandlingException.permanent(FailureStage.ENCODE,
                    field + " is PIC X, one byte per character, and the value contains a character"
                            + " outside ASCII that has no single-byte form");
        }
        return raw;
    }
}
