package bank.tessera.ledger.port;

import java.util.Objects;

/**
 * The response a previously accepted request produced, kept so a retry can be answered with it.
 *
 * <p>Stored as a status and an opaque body rather than as a domain object. Replaying an idempotent
 * request must return the <em>original</em> answer, not a freshly rendered one: re-rendering would
 * pick up anything that has changed since - a balance, a status, a serialisation tweak - and the
 * client would receive a different document for the same request, which is the exact promise
 * idempotency makes and breaks.
 */
public final class StoredResponse {

    private final int status;
    private final String body;

    private StoredResponse(int status, String body) {
        this.status = status;
        this.body = body;
    }

    public static StoredResponse of(int status, String body) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Status must be a valid HTTP status, but was: " + status);
        }
        return new StoredResponse(status, Objects.requireNonNull(body, "body"));
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredResponse that && status == that.status && body.equals(that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, body);
    }

    @Override
    public String toString() {
        return "StoredResponse[" + status + " " + body.length() + " bytes]";
    }
}
