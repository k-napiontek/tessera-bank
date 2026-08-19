package bank.tessera.ledger.api.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.api.LedgerApiTest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

/**
 * Tracing hooks: the spans exist, they reach the logs, and the context leaves on the wire.
 *
 * <p>Deliberately not a test of where traces are shipped. There is no exporter and no collector
 * address in this repository (ADR 0001), so what can be held to account here is the part that is
 * application source: that a span's identifiers appear on the log lines written inside it, and that
 * the context this service hands to the next tier is W3C `traceparent`.
 *
 * <p>The second matters more than it looks. A propagation mismatch does not fail - B3 and W3C simply
 * do not see each other - so the next tier starts a fresh trace, every hop appears as an unrelated
 * request, and the symptom is "tracing is on but nothing joins up".
 */
// The same reason as BusinessMetricsTest: Boot switches observability off inside @SpringBootTest, and
// without this there is no Tracer to assert on.
@AutoConfigureObservability
class TracingTest extends LedgerApiTest {

    @Autowired
    private Tracer tracer;

    @Autowired
    private Propagator propagator;

    private ListAppender<ILoggingEvent> captured;
    private Logger root;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
        root.setLevel(Level.INFO);
    }

    @AfterEach
    void stopCapturing() {
        root.detachAppender(captured);
        captured.stop();
    }

    @Test
    @DisplayName("a log line written inside a span carries that span's trace id")
    void logsCorrelateWithTraces() {
        Span span = tracer.nextSpan().name("a-unit-of-work").start();
        String traceId = span.context().traceId();

        Tracer.SpanInScope scope = tracer.withSpan(span);
        try {
            LoggerFactory.getLogger(TracingTest.class).info("inside a span");
        } finally {
            scope.close();
            span.end();
        }

        // This is what makes a trace usable during an incident: a support engineer starts from a log
        // line and lands on the trace, rather than reading two systems that share no identifier.
        assertThat(List.copyOf(captured.list))
                .filteredOn(event -> "inside a span".equals(event.getFormattedMessage()))
                .isNotEmpty()
                .allSatisfy(event ->
                        assertThat(event.getMDCPropertyMap()).containsEntry("traceId", traceId));
    }

    @Test
    @DisplayName("the context leaves this service as a W3C traceparent header")
    void theContextPropagatesAsW3c() {
        Span span = tracer.nextSpan().name("outbound").start();
        Map<String, String> carrier = new HashMap<>();

        Tracer.SpanInScope scope = tracer.withSpan(span);
        try {
            propagator.inject(span.context(), carrier, Map::put);
        } finally {
            scope.close();
            span.end();
        }

        assertThat(carrier)
                .as("W3C is what the Go gateway and the Python consumer speak; B3 would not be seen")
                .containsKey("traceparent");
        assertThat(carrier).doesNotContainKey("X-B3-TraceId");
        assertThat(carrier.get("traceparent"))
                .as("version, trace id, parent id and flags, as the W3C format defines")
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
        assertThat(carrier.get("traceparent")).contains(span.context().traceId());
    }

    @Test
    @DisplayName("the correlation id and the trace id are both present, and are not the same thing")
    void theTwoIdentifiersCoexist() {
        // Not redundancy. A trace id reaches as far as W3C propagation does, which is the modern
        // tiers; the correlation id also survives the SOAP call and the fixed-width record that the
        // ESB writes for the mainframe, where no tracing exists and none is going to.
        Span span = tracer.nextSpan().name("both").start();
        org.slf4j.MDC.put("correlationId", "5c2f0b1e-0000-4000-8000-000000000001");
        Tracer.SpanInScope scope = tracer.withSpan(span);
        try {
            LoggerFactory.getLogger(TracingTest.class).info("both identifiers");
        } finally {
            scope.close();
            org.slf4j.MDC.remove("correlationId");
            span.end();
        }

        assertThat(List.copyOf(captured.list))
                .filteredOn(event -> "both identifiers".equals(event.getFormattedMessage()))
                .isNotEmpty()
                .allSatisfy(event -> {
                    assertThat(event.getMDCPropertyMap())
                            .containsEntry("correlationId", "5c2f0b1e-0000-4000-8000-000000000001");
                    assertThat(event.getMDCPropertyMap().get("traceId"))
                            .isNotEqualTo("5c2f0b1e-0000-4000-8000-000000000001");
                });
    }
}
