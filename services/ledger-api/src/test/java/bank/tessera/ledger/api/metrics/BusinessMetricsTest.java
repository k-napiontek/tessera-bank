package bank.tessera.ledger.api.metrics;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @Test
    @DisplayName("a first releaseHold is a posting, and only the second one is a replay")
    void releasingAHoldIsNotAReplayTheFirstTime() throws Exception {
        // F-71. releaseHold creates nothing, so it answers 200 whether it did the work or replayed
        // an earlier answer - and this filter used to read every 200 as a replay. A run that
        // released thirty holds reported thirty retries nobody made, and the workload driver
        // inferred it the same way, so the two agreed and the reconciliation looked perfect.
        String vault = open(freshAccountReference(), "ASSET");
        String alice = open(freshAccountReference(), "LIABILITY");
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(vault, alice, 500_00)))
                .andExpect(status().isCreated());

        String holdRef = json.readTree(mvc.perform(post("/v1/accounts/" + alice + "/holds")
                                .header("Idempotency-Key", freshIdempotencyKey())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "amount": { "amountMinor": 100, "currency": "PLN" } }
                                        """))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("holdRef").asText();

        double postedBefore = releaseCount("posted");
        double replayedBefore = releaseCount("replayed");

        String key = freshIdempotencyKey();
        mvc.perform(post("/v1/holds/" + holdRef + "/release").header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotency-Replayed"));

        assertThat(releaseCount("posted"))
                .as("the release happened, so it is a posting")
                .isEqualTo(postedBefore + 1);
        assertThat(releaseCount("replayed")).isEqualTo(replayedBefore);

        // The same key again is the retry a client makes after a timeout, and that one is a replay.
        mvc.perform(post("/v1/holds/" + holdRef + "/release").header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"));

        assertThat(releaseCount("replayed")).isEqualTo(replayedBefore + 1);
        assertThat(releaseCount("posted"))
                .as("the retry did no work, so it is not a second posting")
                .isEqualTo(postedBefore + 1);
    }

    /** The counter for one release outcome, or zero before it has ever been incremented. */
    private double releaseCount(String outcome) throws Exception {
        var result = mvc.perform(get("/actuator/metrics/ledger.transfers")
                        .param("tag", "operation:hold.release")
                        .param("tag", "outcome:" + outcome))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            return 0;
        }
        return json.readTree(result.getResponse().getContentAsString())
                .get("measurements").get(0).get("value").asDouble();
    }
}
