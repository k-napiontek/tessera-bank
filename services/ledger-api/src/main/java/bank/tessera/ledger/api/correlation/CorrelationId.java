package bank.tessera.ledger.api.correlation;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * The id that ties one customer request to every line it produces, in every tier.
 *
 * <p>It lives in the SLF4J {@link MDC} rather than being passed down the call stack. That is the one
 * place a logging framework will read it from without every method signature growing a parameter it
 * does not otherwise need - and the audit chain and the outbox event both want it, neither of them
 * having an HTTP request to ask.
 *
 * <p>The value is always a UUID. A client may send one and it is honoured, because an id invented
 * per tier correlates nothing; anything that is not a UUID is discarded and replaced. That is not
 * pedantry about a format: what a caller sends here is written into log lines and into a Problem
 * document, so accepting an arbitrary string would let a caller choose the contents of this service's
 * logs.
 */
public final class CorrelationId {

    /** The header the OpenAPI document declares on every operation. */
    public static final String HEADER = "X-Correlation-Id";

    /** The MDC key, and therefore the field name in every structured log line. */
    public static final String MDC_KEY = "correlationId";

    private CorrelationId() {}

    /** The id of the request being served on this thread, if there is one. */
    public static Optional<String> current() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * Re-applies the header to a response that has just been reset.
     *
     * <p>{@code HttpServletResponse.reset()} clears the headers along with the body, and two paths
     * here use it to replace a half-written response with a Problem document or a replayed one. The
     * correlation header is set before the chain runs, so it is one of the headers that goes.
     */
    public static void applyTo(HttpServletResponse response) {
        current().ifPresent(id -> response.setHeader(HEADER, id));
    }

    /** The supplied value if it is a UUID, or a new one. Never null. */
    static String resolve(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        try {
            // UUID.fromString is lenient about field widths - it accepts "1-1-1-1-1" - so the round
            // trip is what actually decides, not the parse.
            return UUID.fromString(supplied).toString().equals(supplied)
                    ? supplied
                    : UUID.randomUUID().toString();
        } catch (IllegalArgumentException notAUuid) {
            return UUID.randomUUID().toString();
        }
    }
}
