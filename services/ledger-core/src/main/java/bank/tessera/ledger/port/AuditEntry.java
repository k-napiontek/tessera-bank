package bank.tessera.ledger.port;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One line of the audit trail: who did what to which subject, when, and what changed.
 *
 * <p><strong>The canonical form is the control, not the digest.</strong> Every implementation of
 * SHA-256 agrees with every other; what decides whether a chain is tamper-evident is whether two
 * different entries can produce the same input bytes. A form that concatenated its fields would let
 * {@code {"a": "bc"}} and {@code {"ab": "c"}} hash identically, and an auditor could then be shown
 * either row with the chain verifying for both. So every component is length-prefixed, an absent
 * value is encoded differently from an empty one, and state is emitted in key order.
 *
 * <p>This type lives in {@code ledger-core} with the rest of the ports, so the definition of what is
 * hashed is pure Java and testable in milliseconds. Only the chaining - reading the previous row's
 * hash under a lock - needs a database, and that stays in the adapter.
 *
 * <p><strong>No personal data, ever.</strong> The state maps carry references, statuses, amounts in
 * minor units and currency codes. The ledger holds no customer identity to leak, and an audit row is
 * retained for years, which makes it the worst possible place to start.
 */
public final class AuditEntry {

    /** The predecessor of the first row. */
    public static final String GENESIS_HASH = "0".repeat(64);

    private static final String ENCODING_VERSION = "tessera-audit-v1";

    private final Instant occurredAt;
    private final String actor;
    private final AuditAction action;
    private final String subject;
    private final String correlationId;
    private final Map<String, String> before;
    private final Map<String, String> after;

    private AuditEntry(
            Instant occurredAt,
            String actor,
            AuditAction action,
            String subject,
            String correlationId,
            Map<String, String> before,
            Map<String, String> after) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.subject = subject;
        this.correlationId = correlationId;
        this.before = before;
        this.after = after;
    }

    /**
     * @param subject the reference the action was about - an account, a transfer or a hold
     * @param correlationId the request this happened under, or null if there was none
     * @param before the subject's state before, empty when the action created it
     * @param after the subject's state after
     */
    public static AuditEntry of(
            Instant occurredAt,
            String actor,
            AuditAction action,
            String subject,
            String correlationId,
            Map<String, String> before,
            Map<String, String> after) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(subject, "subject");
        return new AuditEntry(
                occurredAt, actor, action, subject, correlationId, copy(before), copy(after));
    }

    private static Map<String, String> copy(Map<String, String> state) {
        Objects.requireNonNull(state, "state");
        // Sorted and defensive. Sorted so the hash does not depend on the caller's iteration order;
        // copied so a caller that keeps its map cannot change what was recorded after the fact.
        return Collections.unmodifiableSortedMap(new TreeMap<>(state));
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String actor() {
        return actor;
    }

    public AuditAction action() {
        return action;
    }

    public String subject() {
        return subject;
    }

    public Optional<String> correlationId() {
        return Optional.ofNullable(correlationId);
    }

    public Map<String, String> before() {
        return before;
    }

    public Map<String, String> after() {
        return after;
    }

    /** This entry's hash, chained onto {@code previousHash}. Sixty-four lowercase hex characters. */
    public String hashWith(String previousHash) {
        Objects.requireNonNull(previousHash, "previousHash");
        StringBuilder input = new StringBuilder();
        field(input, previousHash);
        field(input, ENCODING_VERSION);
        field(input, occurredAt.toString());
        field(input, actor);
        field(input, action.name());
        field(input, subject);
        field(input, correlationId);
        state(input, before);
        state(input, after);
        return sha256Hex(input.toString());
    }

    private static void state(StringBuilder input, Map<String, String> state) {
        field(input, String.valueOf(state.size()));
        state.forEach((key, value) -> {
            field(input, key);
            field(input, value);
        });
    }

    /**
     * Appends one length-prefixed component.
     *
     * <p>{@code -1} for absent and {@code 0} for empty, because "the gateway sent no correlation id"
     * and "the gateway sent a blank one" are different statements about what happened, and an audit
     * trail that cannot tell them apart is answering a question it was not asked.
     */
    private static void field(StringBuilder input, String value) {
        if (value == null) {
            input.append("-1:").append('\n');
            return;
        }
        input.append(value.length()).append(':').append(value).append('\n');
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256; the JLS requires it. Wrapped rather than declared so callers
            // are not asked to handle an impossibility.
            throw new IllegalStateException("SHA-256 is not available on this JVM.", impossible);
        }
    }

    @Override
    public String toString() {
        return "AuditEntry[" + action + " " + subject + " at " + occurredAt + "]";
    }
}
