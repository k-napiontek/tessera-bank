package bank.tessera.ledger.api.audit;

import bank.tessera.ledger.api.correlation.CorrelationId;
import bank.tessera.ledger.port.AuditContext;
import java.util.Optional;

/**
 * The audit context of an inbound HTTP request.
 *
 * <p>The correlation id comes from the MDC the correlation filter populated, so a use case running
 * outside a request - a sweep, a relay - records no id rather than a stale one belonging to whatever
 * the thread served last.
 *
 * <p>The actor is this service, and is a constant. That is not a placeholder for a real principal:
 * {@code ledger-core} authenticates nobody and holds no customer identity, and the honest answer to
 * "who did this" at this tier is "the API, on behalf of the gateway". Recording a person here would
 * be recording something the ledger cannot attest to. When {@code edge/api-gateway} starts forwarding
 * an authenticated principal, this class is where it arrives.
 */
public final class HttpAuditContext implements AuditContext {

    private static final String ACTOR = "ledger-api";

    @Override
    public String actor() {
        return ACTOR;
    }

    @Override
    public Optional<String> correlationId() {
        return CorrelationId.current();
    }
}
