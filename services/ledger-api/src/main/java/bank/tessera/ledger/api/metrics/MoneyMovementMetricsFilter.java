package bank.tessera.ledger.api.metrics;

import bank.tessera.ledger.api.idempotency.IdempotencyFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The business metrics: how much money moved, how it turned out, and how long it took.
 *
 * <p><strong>Measured at the boundary, not inside the use cases.</strong> Two reasons. The use cases
 * live in {@code ledger-core}, which carries no framework on its compile classpath and must not start
 * now - a Micrometer import there would be the first crack in the rule that makes the domain testable
 * in milliseconds. And what an operator needs to know is what the customer experienced, which is the
 * response, not an internal call that may have been retried or replayed on the way out.
 *
 * <p><strong>A replay is its own outcome, not a success.</strong> The idempotency filter says so on
 * the response, and this reads what it said: counting a replay as a posting would inflate throughput
 * with work nobody did, and hide the signal that actually matters - a rise in replays means clients
 * are timing out on a ledger that is answering too slowly.
 *
 * <p>It used to infer that from a {@code 200} instead, and F-71 records the cost. Four of the five
 * money-moving operations create something and answer {@code 201}, so a {@code 200} from them really
 * is a replay - but {@code releaseHold} creates nothing and answers {@code 200} either way, so every
 * successful release was counted as a retry that never happened. The workload driver inferred it the
 * same way, so the two agreed and the reconciliation looked perfect while both were wrong.
 *
 * <p>Latency is recorded for outcomes that did something. Timing rejections alongside postings mixes
 * a validation failure that returns in a millisecond with a transfer that took two locks, and the
 * resulting percentile describes neither.
 */
public class MoneyMovementMetricsFilter extends OncePerRequestFilter implements Ordered {

    static final String OUTCOME_COUNTER = "ledger.transfers";
    static final String LATENCY_TIMER = "ledger.posting.latency";

    /** The five operations the OpenAPI document marks as money-moving, each with the name it reports. */
    private static final Map<String, String> OPERATIONS = operations();

    private static Map<String, String> operations() {
        // Ordered: the hold patterns are more specific than /v1/transfers and must be tried first.
        Map<String, String> byPattern = new LinkedHashMap<>();
        byPattern.put("/v1/transfers/*/reversals", "reversal");
        byPattern.put("/v1/accounts/*/holds", "hold.place");
        byPattern.put("/v1/holds/*/capture", "hold.capture");
        byPattern.put("/v1/holds/*/release", "hold.release");
        byPattern.put("/v1/transfers", "transfer");
        return Map.copyOf(byPattern);
    }

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final MeterRegistry registry;

    public MoneyMovementMetricsFilter(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public int getOrder() {
        // After the correlation filter and outside the idempotency filter, so a replay is seen as
        // the 200 it is rather than as whatever the original request did.
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || operationOf(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String operation = operationOf(request);
        Timer.Sample sample = Timer.start(registry);
        try {
            chain.doFilter(request, response);
        } finally {
            String outcome = outcomeOf(response.getStatus(), replayed(response));
            registry.counter(OUTCOME_COUNTER, "operation", operation, "outcome", outcome).increment();
            if (!"rejected".equals(outcome)) {
                sample.stop(registry.timer(LATENCY_TIMER, "operation", operation, "outcome", outcome));
            }
        }
    }

    private static String operationOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (Map.Entry<String, String> candidate : OPERATIONS.entrySet()) {
            if (PATHS.match(candidate.getKey(), path)) {
                return candidate.getValue();
            }
        }
        return null;
    }

    /**
     * Whether the idempotency filter answered from its store.
     *
     * <p>Read from the header rather than inferred from a {@code 200}, which is what this filter
     * used to do. {@code releaseHold} creates nothing and therefore answers {@code 200} whether it
     * released the hold or replayed an earlier answer, so the inference reported every successful
     * release as a retry - F-71. Giving that operation a {@code 201} would have made the inference
     * work again at the cost of the contract saying a resource was created when none was.
     */
    private static boolean replayed(HttpServletResponse response) {
        return Boolean.parseBoolean(response.getHeader(IdempotencyFilter.REPLAYED_HEADER));
    }

    private static String outcomeOf(int status, boolean replayed) {
        if (status >= 200 && status < 300 && replayed) {
            return "replayed";
        }
        if (status >= 200 && status < 300) {
            return "posted";
        }
        if (status >= 500) {
            // Separated from "rejected" on purpose: a refused transfer is the ledger working, and a
            // 500 is the ledger failing. One graph that mixes them cannot be alerted on.
            return "failed";
        }
        return "rejected";
    }
}
