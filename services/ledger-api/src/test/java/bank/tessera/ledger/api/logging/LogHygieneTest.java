package bank.tessera.ledger.api.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.tessera.ledger.api.LedgerApiTest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;

/**
 * REQ-DP-002: personal data never reaches a log.
 *
 * <p>A rule stated in a document is not a control, and "we reviewed the log statements" does not
 * survive the next contributor. What can be checked is narrower and stronger: drive a real request
 * carrying a distinctive marker in the one field a customer controls, capture everything that was
 * logged, and assert the marker is not in it.
 *
 * <p>The MDC gets its own assertion. Everything in it is on every line, which is exactly what makes
 * it convenient and exactly what makes it dangerous - a request-scoped map is where somebody will one
 * day park a customer name "just for debugging". An allowlist fails when that happens.
 */
class LogHygieneTest extends LedgerApiTest {

    /**
     * The only MDC keys this service may log. Anything else fails, on purpose.
     *
     * <p>All three are internal identifiers and none can be resolved to a person: {@code
     * correlationId} is minted at the edge, {@code traceId} and {@code spanId} by Micrometer Tracing.
     * They are listed rather than left implicit because tracing is off in this test's context - so
     * without them here the allowlist would silently start failing the day observability is enabled
     * for it, and the obvious fix would be to widen the list without thinking about what was added.
     */
    private static final Set<String> PERMITTED_MDC_KEYS = Set.of("correlationId", "traceId", "spanId");

    private static final ObjectMapper JSON = new ObjectMapper();

    private ListAppender<ILoggingEvent> captured;
    private Logger root;

    @BeforeEach
    void captureEverything() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        // DEBUG rather than the configured INFO: a leak in a debug statement is still a leak, and it
        // is the level somebody switches on during an incident, when the logs are being read most.
        root.setLevel(Level.DEBUG);
    }

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        root.setLevel(Level.INFO);
        captured.stop();
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

    private String everythingLogged() {
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent event : List.copyOf(captured.list)) {
            all.append(event.getFormattedMessage()).append('\n');
            event.getMDCPropertyMap().forEach((key, value) -> all.append(key).append('=').append(value).append('\n'));
            if (event.getThrowableProxy() != null) {
                all.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return all.toString();
    }

    @Test
    @DisplayName("nothing a customer supplied reaches a log line, on the happy path or the error path")
    void customerSuppliedTextIsNeverLogged() throws Exception {
        // Synthetic and unmistakable. If this string turns up anywhere in the captured output, some
        // statement is logging a request body, and the next one will be logging a real remittance
        // message - which under GDPR is free text a payer may have put anything into.
        // Kept under the contract's 35-character limit for this field - a marker the endpoint
        // rejects as too long would make this test pass without ever reaching the code it checks.
        String marker = "PII-MARKER-" + UUID.randomUUID().toString().substring(0, 18);
        String vault = open(freshAccountReference(), "ASSET");
        String alice = open(freshAccountReference(), "LIABILITY");
        String bob = open(freshAccountReference(), "LIABILITY");

        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(vault, alice, 100_00, marker)))
                .andExpect(status().isCreated());

        // And the error path, which is the one that gets tested least and logs most.
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(bob, alice, 10_000_00, marker)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(everythingLogged())
                .as("a log line carrying customer-supplied text is a GDPR incident, not a debug aid")
                .doesNotContain(marker);
    }

    @Test
    @DisplayName("the MDC carries a correlation id and nothing else")
    void theMdcCarriesOnlyWhatIsPermitted() throws Exception {
        String vault = open(freshAccountReference(), "ASSET");
        String alice = open(freshAccountReference(), "LIABILITY");

        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(vault, alice, 5_00, "INV-7")))
                .andExpect(status().isCreated());

        for (ILoggingEvent event : List.copyOf(captured.list)) {
            assertThat(event.getMDCPropertyMap().keySet())
                    .as("everything in the MDC is on every line; the allowlist is what keeps it that way")
                    .isSubsetOf(PERMITTED_MDC_KEYS);
        }
    }

    @Test
    @DisplayName("the configured encoder emits JSON carrying the correlation id")
    void logsAreStructuredJson() throws Exception {
        // logback-spring.xml is configuration, and configuration that fails to load does so quietly:
        // Boot falls back to its own pattern layout and every line is still readable, so nothing
        // looks wrong until an aggregator is asked to parse them.
        LogstashEncoder encoder = configuredEncoder();
        String correlationId = UUID.randomUUID().toString();

        MDC.put("correlationId", correlationId);
        LoggingEvent event;
        try {
            event = new LoggingEvent(
                    LogHygieneTest.class.getName(),
                    (Logger) LoggerFactory.getLogger(LogHygieneTest.class),
                    Level.INFO,
                    "a line",
                    null,
                    null);
            event.setMDCPropertyMap(MDC.getCopyOfContextMap());
        } finally {
            MDC.remove("correlationId");
        }

        JsonNode line = JSON.readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));

        assertThat(line.get("message").asText()).isEqualTo("a line");
        assertThat(line.get("correlationId").asText()).isEqualTo(correlationId);
        assertThat(line.get("service").asText()).isEqualTo("ledger-api");
        assertThat(line.has("timestamp")).isTrue();
        assertThat(line.has("level")).isTrue();
    }

    private static LogstashEncoder configuredEncoder() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        for (Iterator<Appender<ILoggingEvent>> it = rootLogger.iteratorForAppenders(); it.hasNext(); ) {
            Appender<ILoggingEvent> appender = it.next();
            if (appender instanceof OutputStreamAppender<ILoggingEvent> stream
                    && stream.getEncoder() instanceof LogstashEncoder encoder) {
                return encoder;
            }
        }
        throw new AssertionError(
                "No LogstashEncoder is attached to the root logger. logback-spring.xml did not load, "
                        + "and Boot's default pattern layout is in use instead.");
    }

    private static String transferBody(String debit, String credit, long minor, String reference) {
        return """
                {
                  "debitAccountRef": "%s",
                  "creditAccountRef": "%s",
                  "amount": { "amountMinor": %d, "currency": "PLN" },
                  "reference": "%s"
                }
                """
                .formatted(debit, credit, minor, reference);
    }
}
