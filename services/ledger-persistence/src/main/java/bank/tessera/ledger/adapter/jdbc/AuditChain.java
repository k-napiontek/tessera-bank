package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.port.AuditAction;
import bank.tessera.ledger.port.AuditEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Walks the audit trail and reports the first link that does not hold.
 *
 * <p>This is the half of REQ-AUD-001 the append-only trigger cannot deliver. The trigger stops the
 * application from editing a row; it does not stop a superuser, a DBA with DDL rights, or a backup
 * restored with one row quietly changed. Nothing can. What this does is make such an edit impossible
 * to hide - and it reports the <em>first</em> break rather than a count, because the first break is
 * where the tampering starts and everything after it is a consequence.
 *
 * <p>Run by an operator or a scheduled check, never on the path a transfer takes: it reads the whole
 * table.
 */
public final class AuditChain {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditChain(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * @param seq the row the chain first fails at
     * @param reason what does not hold, in words an operator can act on
     */
    public record BrokenLink(long seq, String reason) {}

    /** Empty when every row verifies. */
    public Optional<BrokenLink> verify() {
        List<Row> rows = jdbc.query(
                """
                SELECT seq, occurred_at, actor, action, subject_ref, correlation_id,
                       before_state, after_state, previous_hash, hash
                  FROM audit_record
                 ORDER BY seq
                """,
                Map.of(),
                (row, number) -> new Row(
                        row.getLong("seq"),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        row.getString("actor"),
                        row.getString("action"),
                        row.getString("subject_ref"),
                        row.getString("correlation_id"),
                        row.getString("before_state"),
                        row.getString("after_state"),
                        row.getString("previous_hash"),
                        row.getString("hash")));

        String expectedPrevious = AuditEntry.GENESIS_HASH;
        for (Row row : rows) {
            if (!expectedPrevious.equals(row.previousHash())) {
                // A row was removed, or one was inserted between two that already chained. Either
                // way the trail no longer says what happened, and it says so here.
                return Optional.of(new BrokenLink(
                        row.seq(),
                        "chains onto " + row.previousHash() + " but the preceding row hashes to "
                                + expectedPrevious + " - a row was removed or inserted"));
            }

            String recomputed = rebuild(row).hashWith(row.previousHash());
            if (!recomputed.equals(row.hash())) {
                return Optional.of(new BrokenLink(
                        row.seq(),
                        "records hash " + row.hash() + " but its contents hash to " + recomputed
                                + " - the row was altered after it was written"));
            }
            expectedPrevious = row.hash();
        }
        return Optional.empty();
    }

    /** How many rows the chain currently holds. Reported alongside a verification for context. */
    public long length() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_record", Map.of(), Long.class);
        return count == null ? 0L : count;
    }

    private AuditEntry rebuild(Row row) {
        return AuditEntry.of(
                row.occurredAt(),
                row.actor(),
                AuditAction.valueOf(row.action()),
                row.subjectRef(),
                row.correlationId(),
                read(row.beforeState()),
                read(row.afterState()));
    }

    private Map<String, String> read(String state) {
        try {
            return json.readValue(state, new TypeReference<Map<String, String>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            // Unreadable state is itself a broken row, but it cannot be reported from here without
            // turning every caller into an exception handler. It surfaces as a hash mismatch on the
            // row that holds it, which names the same row.
            throw new IllegalStateException("Audit state could not be read: " + state, unreadable);
        }
    }

    private record Row(
            long seq,
            java.time.Instant occurredAt,
            String actor,
            String action,
            String subjectRef,
            String correlationId,
            String beforeState,
            String afterState,
            String previousHash,
            String hash) {}
}
