package bank.tessera.ledger.port;

import java.util.Optional;

/**
 * The record of which money-moving requests have already been carried out.
 *
 * <p>This is what makes a retry safe. A client whose connection dropped mid-transfer has no way to
 * know whether the money moved, so it retries with the same {@code Idempotency-Key} - and must get
 * the original answer rather than a second transfer.
 *
 * <p><strong>Claim, then work, then store, all in one transaction.</strong> {@link #claim} either
 * takes the key or reports what the key already produced, and it must do so under a uniqueness
 * constraint rather than a read followed by a write. Two concurrent retries both pass a
 * read-then-write check, both find nothing, and both post - a double spend produced by code that
 * looks correct on the page.
 *
 * <p>The fingerprint is an opaque digest of the request the caller sent. Computing it is the web
 * adapter's job: it involves the HTTP method, the path and a canonical form of the JSON body, none
 * of which the domain knows about or should.
 */
public interface IdempotencyStore {

    /**
     * Takes the key for this request, or reports what a previous request with it produced.
     *
     * @return empty when the key is new and the caller should do the work; the stored response when
     *     the same key and the same fingerprint have already been carried out
     * @throws IdempotencyConflictException if the key was used before
     *     with a different fingerprint - a client defect, not a retry
     */
    Optional<StoredResponse> claim(String key, String fingerprint);

    /**
     * Attaches the outcome to a key claimed earlier in the same transaction.
     *
     * @throws IllegalStateException if the key was never claimed
     */
    void store(String key, StoredResponse response);
}
