package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.port.AuditEntry;
import bank.tessera.ledger.port.AuditLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The audit trail, against PostgreSQL.
 *
 * <p><strong>What is hashed must be what is stored.</strong> Two conversions happen on the way into
 * the table and both would otherwise break verification: {@code timestamptz} keeps microseconds while
 * {@link java.time.Instant} carries nanoseconds, and a {@code uuid} column returns its canonical
 * lowercase form whatever case it was given. So the entry is normalised <em>before</em> it is hashed,
 * and the row that comes back out hashes to the value written beside it. Hash first and normalise
 * second and the chain fails to verify on rows nobody touched - a control that cries wolf gets
 * switched off.
 *
 * <p><strong>The chain is serialised by an advisory lock held to commit.</strong> Reading the last
 * hash and inserting the row that chains onto it have to be one atomic step, or two concurrent
 * appends read the same predecessor and one of them is lost - {@code audit_record_previous_unique}
 * would then reject the second transfer outright, turning a chain defect into a customer-visible
 * failure. The cost is real and is stated rather than hidden: every money-moving transaction queues
 * behind this lock for the duration of its own transaction. A per-subject chain would remove the
 * queue and weaken the guarantee; the global chain is the one an auditor asked for.
 */
public final class JdbcAuditLog implements AuditLog {

    /**
     * The advisory lock the whole chain serialises on.
     *
     * <p>An arbitrary constant, and it only has to be a constant. It is namespaced by the ticket that
     * introduced it so that a second advisory lock added later does not collide by accident.
     */
    private static final long CHAIN_LOCK = 1_009L;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcAuditLog(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public void append(AuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        AuditEntry stored = normalise(entry);


        jdbc.queryForObject("SELECT pg_advisory_xact_lock(:key)", Map.of("key", CHAIN_LOCK), Object.class);

        String previousHash = jdbc.query(
                        "SELECT hash FROM audit_record ORDER BY seq DESC LIMIT 1",
                        Map.of(),
                        row -> row.next() ? row.getString("hash") : AuditEntry.GENESIS_HASH);

        jdbc.update(
                """
                INSERT INTO audit_record
                    (occurred_at, actor, action, subject_ref, correlation_id, before_state,
                     after_state, previous_hash, hash)
                VALUES
                    (:occurredAt, :actor, :action, :subject, CAST(:correlationId AS uuid),
                     CAST(:before AS jsonb), CAST(:after AS jsonb), :previousHash, :hash)
                """,
                new MapSqlParameterSource()
                        .addValue(
                                "occurredAt",
                                OffsetDateTime.ofInstant(stored.occurredAt(), ZoneOffset.UTC))
                        .addValue("actor", stored.actor())
                        .addValue("action", stored.action().name())
                        .addValue("subject", stored.subject())
                        .addValue("correlationId", stored.correlationId().orElse(null))
                        .addValue("before", write(stored.before()))
                        .addValue("after", write(stored.after()))
                        .addValue("previousHash", previousHash)
                        .addValue("hash", stored.hashWith(previousHash)));
    }

    /** The entry as the table will hold it, so that hashing it and reading it back agree. */
    static AuditEntry normalise(AuditEntry entry) {
        return AuditEntry.of(
                entry.occurredAt().truncatedTo(ChronoUnit.MICROS),
                entry.actor(),
                entry.action(),
                entry.subject(),
                entry.correlationId().map(id -> UUID.fromString(id).toString()).orElse(null),
                entry.before(),
                entry.after());
    }

    private String write(Map<String, String> state) {
        try {
            return json.writeValueAsString(state);
        } catch (JsonProcessingException impossible) {
            // A map of strings to strings. Wrapped rather than declared because no caller can act on
            // it and none should have to catch it.
            throw new IllegalStateException("Audit state could not be serialised: " + state, impossible);
        }
    }
}
