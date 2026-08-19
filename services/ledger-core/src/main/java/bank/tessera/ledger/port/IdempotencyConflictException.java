package bank.tessera.ledger.port;

/**
 * Thrown when an idempotency key is reused with a different request.
 *
 * <p>A client defect rather than a retry, and the contract answers it with {@code 409}. The
 * alternative - carrying out the second request - would move money twice under a key whose entire
 * purpose is to guarantee that it moves once. Answering with the first request's response would be
 * worse still: the client would be told an operation it never asked for had succeeded.
 */
public class IdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String key) {
        // The key is client-supplied and opaque, and the fingerprints are digests of request bodies.
        // Neither belongs in a message that reaches a log, so the message names neither.
        super("Idempotency key " + summarise(key) + " has already been used for a different request.");
    }

    /** Enough to correlate with the client's own records, not enough to reproduce the key. */
    private static String summarise(String key) {
        return key == null || key.length() <= 8 ? "(redacted)" : key.substring(0, 4) + "...";
    }
}
