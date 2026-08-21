package bank.tessera.ledger.loader;

import bank.tessera.ledger.loader.LedgerRows.AuditRow;
import bank.tessera.ledger.port.AuditAction;
import bank.tessera.ledger.port.AuditEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;

/**
 * Builds the audit trail a load has to leave behind.
 *
 * <p><strong>An entry without an audit row is not wrong, it is invisible.</strong> Every query in
 * {@code batch/reporting} bounds its postings by joining {@code journal_entry} to
 * {@code audit_record} on {@code subject_ref} with {@code seq <= position}, so a loader that skipped
 * the chain would produce five million postings that no report can see - without breaking a single
 * test in {@code services/}. Follow-up F-43 records exactly that exposure; this class is what stops
 * this package walking into it.
 *
 * <p>The canonical form is {@code AuditEntry}'s and not a copy of it. That is the whole reason the
 * loader is a Java module: what is hashed decides whether two different entries can produce the same
 * bytes, and a second implementation of it - in Go, in Python, anywhere - would agree with the first
 * until the day it did not, at which point the chain would verify against nothing.
 *
 * <p>No advisory lock, unlike {@code JdbcAuditLog}. The lock exists so that concurrent appends cannot
 * interleave and sequence order stays chain order; a bulk load has exactly one writer, which is the
 * single thing about it that is easier rather than harder.
 */
final class ChainWriter {

    private final ObjectMapper json = new ObjectMapper();
    private final String actor;
    private String previousHash = AuditEntry.GENESIS_HASH;
    private long length;

    ChainWriter(String actor) {
        this.actor = actor;
    }

    /**
     * The next link.
     *
     * @param correlationId always null here: a bulk load has no inbound request, and "no correlation
     *     id" is encoded differently from "a blank one" precisely so the trail cannot claim otherwise
     */
    AuditRow next(
            Instant occurredAt,
            AuditAction action,
            String subject,
            Map<String, String> before,
            Map<String, String> after) {
        AuditEntry entry = AuditEntry.of(occurredAt, actor, action, subject, null, before, after);
        String hash = entry.hashWith(previousHash);
        AuditRow row = new AuditRow(
                occurredAt,
                actor,
                action.name(),
                subject,
                null,
                write(before),
                write(after),
                previousHash,
                hash);
        previousHash = hash;
        length++;
        return row;
    }

    /** How many links the chain holds. */
    long length() {
        return length;
    }

    /** The hash the next row will chain onto. */
    String head() {
        return previousHash;
    }

    private String write(Map<String, String> state) {
        try {
            return json.writeValueAsString(state);
        } catch (JsonProcessingException impossible) {
            // Every value is a String and every key is a String. Wrapped rather than declared so no
            // caller is asked to handle an impossibility.
            throw new IllegalStateException("Audit state could not be written: " + state, impossible);
        }
    }
}
