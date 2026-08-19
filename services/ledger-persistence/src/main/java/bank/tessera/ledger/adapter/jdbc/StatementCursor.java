package bank.tessera.ledger.adapter.jdbc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Objects;

/**
 * The sort key of one statement movement, encoded so a client can hand it back.
 *
 * <p>Four parts, and all four are needed for a <em>total</em> order: value date, the instant the
 * ledger recorded the entry, the entry reference and the leg number. Two movements can share the
 * first, or the first two; none can share all four, because {@code posting_seq_uq} makes
 * {@code (entry_ref, seq)} unique. A key that were merely mostly-unique would let two rows occupy one
 * position, and the boundary between two pages would then be ambiguous - the same skip-and-duplicate
 * defect that rules out an offset in the first place.
 *
 * <p><strong>The encoding is not a contract.</strong> It is base64 so that it survives a query string
 * and so that it does not invite parsing; a client that decoded one would be coupled to this ledger's
 * sort key, and the sort key is free to change. A cursor that does not decode is rejected rather than
 * interpreted, because guessing what a malformed cursor meant is how a statement returns somebody
 * else's movements.
 */
final class StatementCursor {

    /** Safe as a delimiter: an entry reference matches {@code ^TB[0-9]{18}$} and a date and a number contain no bars. */
    private static final String SEPARATOR = "|";

    private static final int MICROS_PER_SECOND = 1_000_000;
    private static final int NANOS_PER_MICRO = 1_000;

    private final LocalDate valueDate;
    private final Instant postedAt;
    private final String entryReference;
    private final int seq;

    StatementCursor(LocalDate valueDate, Instant postedAt, String entryReference, int seq) {
        this.valueDate = Objects.requireNonNull(valueDate, "valueDate");
        this.postedAt = Objects.requireNonNull(postedAt, "postedAt");
        this.entryReference = Objects.requireNonNull(entryReference, "entryReference");
        this.seq = seq;
    }

    String encode() {
        String plain = String.join(
                SEPARATOR,
                valueDate.toString(),
                Long.toString(micros(postedAt)),
                entryReference,
                Integer.toString(seq));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    static StatementCursor decode(String encoded) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = plain.split("\\|", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("expected four parts, found " + parts.length);
            }
            return new StatementCursor(
                    LocalDate.parse(parts[0]),
                    fromMicros(Long.parseLong(parts[1])),
                    parts[2],
                    Integer.parseInt(parts[3]));
        } catch (RuntimeException malformed) {
            // The cause is deliberately dropped. An error carrying a decoded fragment of somebody's
            // cursor is an error that leaks into a log, and the caller can do nothing with it anyway.
            throw new IllegalArgumentException("Statement cursor was not issued by this ledger.");
        }
    }

    LocalDate valueDate() {
        return valueDate;
    }

    Instant postedAt() {
        return postedAt;
    }

    String entryReference() {
        return entryReference;
    }

    int seq() {
        return seq;
    }

    /**
     * PostgreSQL stores microseconds and {@link Instant} carries nanoseconds. Encoding at microsecond
     * precision keeps the cursor comparable with the column it is compared against. Encoding
     * nanoseconds would produce a key strictly greater than the row it names, and that row's successor
     * would be skipped - one movement missing from a statement, and nothing reporting it.
     */
    private static long micros(Instant instant) {
        return Math.multiplyExact(instant.getEpochSecond(), (long) MICROS_PER_SECOND)
                + instant.getNano() / NANOS_PER_MICRO;
    }

    private static Instant fromMicros(long value) {
        return Instant.ofEpochSecond(
                Math.floorDiv(value, MICROS_PER_SECOND),
                (long) Math.floorMod(value, MICROS_PER_SECOND) * NANOS_PER_MICRO);
    }
}
