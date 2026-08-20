package bank.tessera.backoffice.mainframe;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads {@code REJECTS.DAT} - every movement the overnight cycle could not apply.
 *
 * <p>{@code REJREC} is 200 bytes: a whole {@code MOVEREC} in the first 120, then the reason code,
 * the operator-readable text and the detection timestamp. The offsets below are declared here and
 * the test derives them from {@code contracts/check-copybook-offsets.py --json}, which is the same
 * division {@code MovementRecord} uses at stratum 2 - a reader whose offsets were transcribed agrees
 * with the transcription rather than with the contract.
 *
 * <p><strong>A file that is not a whole number of records is refused, not partially listed.</strong>
 * A rejects screen showing eleven of twelve rejects and no error is worse than one showing none:
 * the twelfth is the one nobody works.
 */
public final class RejectFile {

    /** {@code REJREC} is 200 bytes. Asserted against the contracts checker by the test. */
    public static final int LENGTH = 200;

    /** {@code MOVEREC}, embedded whole as {@code REJ-MOVEMENT}. */
    private static final int MOVEMENT_AT = 0;
    private static final int MOV_TRANSFER_REF_AT = 0;
    private static final int MOV_TRANSFER_REF_SIZE = 20;
    private static final int MOV_LEG_NO_AT = 20;
    private static final int MOV_LEG_NO_SIZE = 2;
    private static final int MOV_ACCT_REF_AT = 22;
    private static final int MOV_ACCT_REF_SIZE = 16;
    private static final int MOV_DIRECTION_AT = 38;
    private static final int MOV_DIRECTION_SIZE = 1;
    private static final int MOV_CURRENCY_AT = 39;
    private static final int MOV_CURRENCY_SIZE = 3;
    private static final int MOV_AMOUNT_AT = 42;
    private static final int MOV_VALUE_DATE_AT = 50;
    private static final int MOV_VALUE_DATE_SIZE = 8;

    private static final int REJ_REASON_CODE_AT = 120;
    private static final int REJ_REASON_CODE_SIZE = 4;
    private static final int REJ_REASON_TEXT_AT = 124;
    private static final int REJ_REASON_TEXT_SIZE = 40;
    private static final int REJ_DETECTED_TS_AT = 164;
    private static final int REJ_DETECTED_TS_SIZE = 14;

    private RejectFile() {
    }

    /** Every reject in the file, in the order the cycle wrote them. */
    public static List<Reject> read(File path) throws IOException {
        byte[] raw = readAll(path);
        if (raw.length % LENGTH != 0) {
            throw new IOException(path + " is " + raw.length + " bytes, which is not a whole number"
                    + " of " + LENGTH + "-byte REJREC records (" + (raw.length % LENGTH)
                    + " bytes over). It is not read: a rejects list missing its last entry is worse"
                    + " than none, because that is the one nobody works.");
        }

        List<Reject> rejects = new ArrayList<Reject>();
        for (int start = 0; start < raw.length; start += LENGTH) {
            rejects.add(record(raw, start, path, (start / LENGTH) + 1));
        }
        return Collections.unmodifiableList(rejects);
    }

    private static Reject record(byte[] raw, int start, File path, int number) throws IOException {
        try {
            int movement = start + MOVEMENT_AT;
            return new Reject(
                    text(raw, movement + MOV_TRANSFER_REF_AT, MOV_TRANSFER_REF_SIZE),
                    Integer.parseInt(text(raw, movement + MOV_LEG_NO_AT, MOV_LEG_NO_SIZE)),
                    text(raw, movement + MOV_ACCT_REF_AT, MOV_ACCT_REF_SIZE),
                    text(raw, movement + MOV_DIRECTION_AT, MOV_DIRECTION_SIZE),
                    text(raw, movement + MOV_CURRENCY_AT, MOV_CURRENCY_SIZE),
                    Comp3.decode(raw, movement + MOV_AMOUNT_AT),
                    text(raw, movement + MOV_VALUE_DATE_AT, MOV_VALUE_DATE_SIZE),
                    text(raw, start + REJ_REASON_CODE_AT, REJ_REASON_CODE_SIZE),
                    text(raw, start + REJ_REASON_TEXT_AT, REJ_REASON_TEXT_SIZE),
                    text(raw, start + REJ_DETECTED_TS_AT, REJ_DETECTED_TS_SIZE));
        } catch (RuntimeException problem) {
            // The record number, because "record 9" is something an operator can seek to and a byte
            // offset is something they would have to divide.
            throw new IOException(path + " record " + number + ": " + problem.getMessage(), problem);
        }
    }

    /**
     * ASCII rather than the platform default. A display field on this file is single-byte by
     * definition, and a container started in a different locale must not read it differently.
     */
    private static String text(byte[] raw, int at, int size) {
        try {
            return new String(raw, at, size, "US-ASCII").trim();
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException("US-ASCII is required of every JVM", impossible);
        }
    }

    private static byte[] readAll(File path) throws IOException {
        InputStream stream = new FileInputStream(path);
        try {
            java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                collected.write(buffer, 0, read);
            }
            return collected.toByteArray();
        } finally {
            stream.close();
        }
    }

    /** The declared layout, so the contract test can hold it to the copybook. */
    public static int[] offsetsOf(String field) {
        if ("REJ-MOVEMENT".equals(field)) {
            return new int[] {MOVEMENT_AT, 120};
        }
        if ("REJ-REASON-CODE".equals(field)) {
            return new int[] {REJ_REASON_CODE_AT, REJ_REASON_CODE_SIZE};
        }
        if ("REJ-REASON-TEXT".equals(field)) {
            return new int[] {REJ_REASON_TEXT_AT, REJ_REASON_TEXT_SIZE};
        }
        if ("REJ-DETECTED-TS".equals(field)) {
            return new int[] {REJ_DETECTED_TS_AT, REJ_DETECTED_TS_SIZE};
        }
        return null;
    }
}
