package bank.tessera.ledger.domain;

/**
 * The lifecycle of an account, as far as posting is concerned.
 *
 * <p>Traces to {@code Account.status} in docs/architecture/canonical-data-model.md.
 */
public enum AccountStatus {

    /** Accepting postings. */
    OPEN,

    /** Exists, but no posting may touch it - a court order, a fraud freeze, a compliance hold. */
    BLOCKED,

    /** Closed for good. History is retained; nothing new is posted. */
    CLOSED;

    public boolean allowsPosting() {
        return this == OPEN;
    }
}
