package bank.tessera.ledger.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.tessera.ledger.adapter.jdbc.AuditChain;
import bank.tessera.ledger.api.LedgerApiTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The audit trail as it is actually produced: over HTTP, against real PostgreSQL, inside the
 * transaction the request runs in.
 *
 * <p>{@code AuditTrailTest} in {@code ledger-core} proves which entries each use case appends. This
 * proves the two claims that need a database to be true at all - the correlation id of the inbound
 * request reaches the row, and a rejected transfer leaves nothing behind once its transaction has
 * rolled back.
 */
class AuditTrailEndToEndTest extends LedgerApiTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private String open(String reference, String type) throws Exception {
        String body = """
                {
                  "accountRef": "%s",
                  "customerRef": "CU0000000001",
                  "accountType": "%s",
                  "currency": "PLN"
                }
                """
                .formatted(reference, type);
        mvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return reference;
    }

    private static String transferBody(String debit, String credit, long minor) {
        return """
                {
                  "debitAccountRef": "%s",
                  "creditAccountRef": "%s",
                  "amount": { "amountMinor": %d, "currency": "PLN" }
                }
                """
                .formatted(debit, credit, minor);
    }

    private long auditRows() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM audit_record", Map.of(), Long.class);
        return count == null ? 0L : count;
    }

    @Test
    @DisplayName("a posted transfer leaves an audit row carrying the request's correlation id")
    void aTransferIsAudited() throws Exception {
        String vault = open(freshAccountReference(), "ASSET");
        String alice = open(freshAccountReference(), "LIABILITY");
        String correlationId = UUID.randomUUID().toString();

        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(vault, alice, 100_00)))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM audit_record WHERE correlation_id = CAST(:id AS uuid)"
                                + " AND action = 'TRANSFER_POSTED'",
                        Map.of("id", correlationId),
                        Long.class))
                .isEqualTo(1L);
        assertThat(new AuditChain(jdbc, objectMapper).verify()).isEmpty();
    }

    @Test
    @DisplayName("a rejected transfer leaves no audit row behind")
    void aRejectedTransferLeavesNothing() throws Exception {
        String alice = open(freshAccountReference(), "LIABILITY");
        String bob = open(freshAccountReference(), "LIABILITY");
        long before = auditRows();

        // Alice has no money and no overdraft, so this is refused inside the transaction, after the
        // accounts have been locked and read. Everything the request touched goes back with it.
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice, bob, 10_000_00)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(auditRows()).isEqualTo(before);
        assertThat(new AuditChain(jdbc, objectMapper).verify())
                .as("a chain with a gap in it would fail here, not merely hold fewer rows")
                .isEmpty();
    }
}
