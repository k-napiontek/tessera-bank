package bank.tessera.ledger.api.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.tessera.ledger.api.LedgerApiTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * One request, one id, on every path through the service.
 *
 * <p>The contract declares {@code X-Correlation-Id} optional on every operation and says the gateway
 * generates it when absent. "Optional" is about the client, not about the service: a request that
 * arrives without one still has to be traceable, so the ledger generates its own rather than logging
 * a blank field and hoping.
 */
class CorrelationIdTest extends LedgerApiTest {

    /** Nothing opens this account, so the request always reaches the Problem handler. */
    private static final String UNKNOWN_ACCOUNT = "TB00000000999999";

    @Test
    @DisplayName("a request without the header still gets an id back")
    void generatesAnIdWhenTheClientSendsNone() throws Exception {
        MvcResult result = mvc.perform(get("/v1/accounts/" + UNKNOWN_ACCOUNT))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn();

        String returned = result.getResponse().getHeader("X-Correlation-Id");
        assertThatCode(() -> UUID.fromString(returned))
                .as("a generated correlation id must be a UUID, as the contract's schema says")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an id the client supplies is the id that comes back")
    void echoesAValidClientId() throws Exception {
        String supplied = UUID.randomUUID().toString();

        mvc.perform(get("/v1/accounts/" + UNKNOWN_ACCOUNT).header("X-Correlation-Id", supplied))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", supplied));
    }

    @Test
    @DisplayName("a malformed id is replaced rather than propagated")
    void replacesAMalformedClientId() throws Exception {
        // Whatever a caller sends ends up in log lines and in a Problem document. Propagating an
        // arbitrary string would let a client choose what this service logs, which is a log-injection
        // hole, and it would break the join with every other tier that keys on a UUID.
        MvcResult result = mvc.perform(
                        get("/v1/accounts/" + UNKNOWN_ACCOUNT).header("X-Correlation-Id", "not-a-uuid"))
                .andExpect(status().isNotFound())
                .andReturn();

        String returned = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(returned).isNotEqualTo("not-a-uuid");
        assertThatCode(() -> UUID.fromString(returned)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the Problem document carries the id the response header carries")
    void theProblemDocumentCarriesTheResolvedId() throws Exception {
        MvcResult result = mvc.perform(get("/v1/accounts/" + UNKNOWN_ACCOUNT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").exists())
                .andReturn();

        String header = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(result.getResponse().getContentAsString()).contains("\"correlationId\":\"" + header + "\"");
    }

    @Test
    @DisplayName("the MDC is empty once the request is over")
    void clearsTheMdcAfterwards() throws Exception {
        // MockMvc runs the filter chain on this thread. A correlation id left behind would be
        // attached to whatever that thread served next, which in a pooled container is somebody
        // else's request - the one failure mode of MDC that matters.
        mvc.perform(get("/v1/accounts/" + UNKNOWN_ACCOUNT)).andExpect(status().isNotFound());

        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("the id survives a response the idempotency filter resets")
    void survivesTheConflictPath() throws Exception {
        // ProblemWriter calls response.reset(), which clears every header already set - including the
        // one this filter set before the chain ran. The conflict path is written by a filter rather
        // than by the advice, so it is the one place where that loss would go unnoticed.
        String debit = open(freshAccountReference(), "ASSET");
        String credit = open(freshAccountReference(), "LIABILITY");
        String key = freshIdempotencyKey();

        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(debit, credit, 10_00)))
                .andExpect(status().isCreated());

        MvcResult conflict = mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(debit, credit, 20_00)))
                .andExpect(status().isConflict())
                .andExpect(header().exists("X-Correlation-Id"))
                .andReturn();

        String header = conflict.getResponse().getHeader("X-Correlation-Id");
        assertThat(conflict.getResponse().getContentAsString())
                .contains("\"correlationId\":\"" + header + "\"");
    }

    @Test
    @DisplayName("a replay carries the correlation id of the replay, not of the original")
    void aReplayCarriesItsOwnId() throws Exception {
        String debit = open(freshAccountReference(), "ASSET");
        String credit = open(freshAccountReference(), "LIABILITY");
        String key = freshIdempotencyKey();
        String body = transferBody(debit, credit, 10_00);

        String first = UUID.randomUUID().toString();
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", key)
                        .header("X-Correlation-Id", first)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // The replay's body is the original's, byte for byte - that is what idempotency promises.
        // The correlation id is not part of that body: it identifies this request, and a support
        // engineer tracing the retry needs the retry's id rather than the one it replaced.
        String second = UUID.randomUUID().toString();
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", key)
                        .header("X-Correlation-Id", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", second));
    }

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
}
