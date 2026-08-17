package bank.tessera.ledger.domain;

/**
 * Thrown when a posting would take an account further negative than its overdraft policy allows.
 *
 * <p>A business outcome rather than a failure: the caller is expected to turn this into a rejection
 * the customer can understand, not a stack trace.
 */
public class OverdraftNotPermittedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OverdraftNotPermittedException(Money resulting, OverdraftPolicy policy) {
        super("Posting would leave a balance of " + resulting.toPlainString() + " " + resulting.currency()
                + ", which exceeds the account's policy of " + policy);
    }
}
