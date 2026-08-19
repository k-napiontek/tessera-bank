package bank.tessera.ledger.port;

import java.util.Optional;

/**
 * Who is asking, and under which request.
 *
 * <p>A port because a use case must not know how either is discovered. In {@code ledger-api} the
 * correlation id comes from the MDC that the inbound filter populated; in a batch there is no
 * request and no id, and the audit row says so rather than inventing one.
 *
 * <p><strong>The actor is a system, not a person.</strong> This tier authenticates nobody -
 * {@code edge/api-gateway} does that, and the ledger holds no customer identity at all. Recording a
 * person here would be recording something the ledger cannot attest to, which is worse in an audit
 * trail than recording less. When the gateway starts forwarding an authenticated principal, this is
 * the seam it arrives through.
 */
public interface AuditContext {

    /** The calling system, as this tier can honestly name it. */
    String actor();

    /** The request all of this is happening under, or empty when there is none. */
    Optional<String> correlationId();
}
