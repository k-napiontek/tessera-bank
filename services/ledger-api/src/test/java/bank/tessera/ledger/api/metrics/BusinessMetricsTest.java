package bank.tessera.ledger.api.metrics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.tessera.ledger.api.LedgerApiTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The business metrics, read the way a scrape reads them.
 *
 * <p>Asserted through `/actuator/metrics` rather than against the registry object, because the
 * registry holding a meter and the endpoint exposing it are different facts: the exposure list in
 * `application.yml` decides the second, and a metric nothing can scrape is a metric nobody has.
 *
 * <p>The tags are the point. "How many transfers" is not a useful number; "how many were posted,
 * rejected or replayed" is what an operator acts on, and each of those is asserted separately here
 * so that a filter which counted every request as a success would fail rather than merely be
 * imprecise.
 */
// Spring Boot switches metrics *export* off inside @SpringBootTest - deliberately, so that a test
// run cannot push to a real backend - and leaves only a SimpleMeterRegistry behind. Without this
// annotation /actuator/prometheus does not exist, the scrape assertion below cannot be written, and
// the exposure configuration in application.yml would be verified by nothing at all.
@AutoConfigureObservability
class BusinessMetricsTest extends LedgerApiTest {

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

    @Test
    @DisplayName("a posting, a rejection and a replay are counted separately and are all scrapeable")
    void everyOutcomeIsCountedUnderItsOwnTag() throws Exception {
        String vault = open(freshAccountReference(), "ASSET");
        String alice = open(freshAccountReference(), "LIABILITY");
        String bob = open(freshAccountReference(), "LIABILITY");
        String replayKey = freshIdempotencyKey();

        // Posted.
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", replayKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(vault, alice, 100_00)))
                .andExpect(status().isCreated());

        // Replayed - the same key and the same body, which is what a client does after a timeout.
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", replayKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(vault, alice, 100_00)))
                .andExpect(status().isOk());

        // Rejected - bob has nothing and no overdraft.
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(bob, alice, 10_000_00)))
                .andExpect(status().isUnprocessableEntity());

        assertCounted("posted");
        assertCounted("replayed");
        assertCounted("rejected");
    }

    private void assertCounted(String outcome) throws Exception {
        mvc.perform(get("/actuator/metrics/ledger.transfers")
                        .param("tag", "operation:transfer")
                        .param("tag", "outcome:" + outcome))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[?(@.statistic == 'COUNT')].value")
                        .value(Matchers.hasItem(Matchers.greaterThanOrEqualTo(1.0))));
    }

    @Test
    @DisplayName("posting latency is timed, and a rejection is not timed alongside it")
    void latencyIsRecordedForWorkThatHappened() throws Exception {
        String vault = open(freshAccountReference(), "ASSET");
        String alice = open(freshAccountReference(), "LIABILITY");

        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(vault, alice, 25_00)))
                .andExpect(status().isCreated());

        mvc.perform(get("/actuator/metrics/ledger.posting.latency")
                        .param("tag", "operation:transfer")
                        .param("tag", "outcome:posted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[?(@.statistic == 'TOTAL_TIME')].value")
                        .value(Matchers.hasItem(Matchers.greaterThan(0.0))));

        // Mixing a validation failure that returns in a millisecond with a transfer that took two
        // locks produces a percentile describing neither, so rejections are counted and not timed.
        mvc.perform(get("/actuator/metrics/ledger.posting.latency")
                        .param("tag", "operation:transfer")
                        .param("tag", "outcome:rejected"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the outbox exposes its depth and its age")
    void outboxLagIsScrapeable() throws Exception {
        String vault = open(freshAccountReference(), "ASSET");
        String alice = open(freshAccountReference(), "LIABILITY");

        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(vault, alice, 10_00)))
                .andExpect(status().isCreated());

        // The relay is off in this context, so the event is still waiting - which is exactly the
        // state these gauges exist to report.
        mvc.perform(get("/actuator/metrics/ledger.outbox.pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measurements[0].value")
                        .value(Matchers.greaterThanOrEqualTo(1.0)));

        mvc.perform(get("/actuator/metrics/ledger.outbox.lag"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUnit").value("seconds"));
    }

    @Test
    @DisplayName("the prometheus endpoint carries the business metrics, not only the JVM's")
    void theScrapeEndpointCarriesThem() throws Exception {
        String vault = open(freshAccountReference(), "ASSET");
        String alice = open(freshAccountReference(), "LIABILITY");

        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(vault, alice, 30_00)))
                .andExpect(status().isCreated());

        String scrape = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(scrape)
                .contains("ledger_transfers_total")
                .contains("ledger_outbox_pending")
                .contains("ledger_outbox_lag_seconds")
                .contains("application=\"ledger-api\"");
    }
}
