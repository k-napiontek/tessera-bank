package bank.tessera.ledger.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the audit chain hashes, and why the encoding matters more than the digest.
 *
 * <p>SHA-256 is not the interesting part - every implementation of it agrees. The interesting part is
 * what goes into it: if two different entries can produce the same input bytes, the chain is
 * tamper-evident in name only, because one row can be swapped for the other and every hash still
 * verifies. So the canonical form is length-prefixed, and the test that matters here is the one that
 * feeds it two entries a naive concatenation would flatten together.
 */
class AuditEntryTest {

    private static final Instant AT = Instant.parse("2026-08-19T10:00:00Z");
    private static final String GENESIS = "0".repeat(64);

    private static AuditEntry entry(Map<String, String> before, Map<String, String> after) {
        return AuditEntry.of(
                AT,
                "gateway",
                AuditAction.TRANSFER_POSTED,
                "TB00000000000001",
                "5c2f0b1e-0000-4000-8000-000000000001",
                before,
                after);
    }

    @Test
    @DisplayName("the same entry hashes the same way twice")
    void hashingIsDeterministic() {
        AuditEntry one = entry(Map.of("balance", "100"), Map.of("balance", "150"));
        AuditEntry two = entry(Map.of("balance", "100"), Map.of("balance", "150"));

        assertThat(one.hashWith(GENESIS)).isEqualTo(two.hashWith(GENESIS));
    }

    @Test
    @DisplayName("a hash is 64 lowercase hex characters")
    void aHashIsHex() {
        assertThat(entry(Map.of(), Map.of("status", "OPEN")).hashWith(GENESIS)).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the chain moves: the same entry after a different predecessor hashes differently")
    void thePredecessorIsPartOfTheHash() {
        AuditEntry one = entry(Map.of(), Map.of("status", "OPEN"));

        assertThat(one.hashWith(GENESIS)).isNotEqualTo(one.hashWith("f".repeat(64)));
    }

    @Test
    @DisplayName("field boundaries cannot be shifted without changing the hash")
    void theEncodingIsUnambiguous() {
        // The attack a naive encoding loses to. Concatenate key and value and both of these are
        // "abc"; length-prefix them and they are not. If these two collided, an auditor could be
        // shown either row and the chain would verify for both.
        AuditEntry one = entry(Map.of(), Map.of("a", "bc"));
        AuditEntry two = entry(Map.of(), Map.of("ab", "c"));

        assertThat(one.hashWith(GENESIS)).isNotEqualTo(two.hashWith(GENESIS));
    }

    @Test
    @DisplayName("an absent correlation id is not the same as an empty one")
    void nullAndEmptyAreDifferentStatements() {
        AuditEntry absent = AuditEntry.of(
                AT, "gateway", AuditAction.ACCOUNT_OPENED, "TB00000000000001", null, Map.of(), Map.of());
        AuditEntry empty = AuditEntry.of(
                AT, "gateway", AuditAction.ACCOUNT_OPENED, "TB00000000000001", "", Map.of(), Map.of());

        assertThat(absent.hashWith(GENESIS)).isNotEqualTo(empty.hashWith(GENESIS));
    }

    @Test
    @DisplayName("state is hashed in key order, whatever order it was supplied in")
    void stateOrderDoesNotMatter() {
        java.util.LinkedHashMap<String, String> forwards = new java.util.LinkedHashMap<>();
        forwards.put("a", "1");
        forwards.put("b", "2");
        java.util.LinkedHashMap<String, String> backwards = new java.util.LinkedHashMap<>();
        backwards.put("b", "2");
        backwards.put("a", "1");

        assertThat(entry(Map.of(), forwards).hashWith(GENESIS))
                .isEqualTo(entry(Map.of(), backwards).hashWith(GENESIS));
    }

    @Test
    @DisplayName("an entry names what it was about")
    void aSubjectIsRequired() {
        assertThatThrownBy(() -> AuditEntry.of(
                        AT, "gateway", AuditAction.TRANSFER_POSTED, null, null, Map.of(), Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("state values are read-only once the entry is built")
    void stateIsCopiedIn() {
        java.util.HashMap<String, String> mutable = new java.util.HashMap<>();
        mutable.put("status", "OPEN");
        AuditEntry recorded = entry(Map.of(), mutable);
        String before = recorded.hashWith(GENESIS);

        mutable.put("status", "CLOSED");

        assertThat(recorded.hashWith(GENESIS))
                .as("an audit entry that changes after it was recorded is not an audit entry")
                .isEqualTo(before);
    }
}
