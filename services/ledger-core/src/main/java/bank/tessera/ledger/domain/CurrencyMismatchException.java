package bank.tessera.ledger.domain;

/**
 * Thrown when an operation is attempted across two currencies.
 *
 * <p>There is no implicit conversion anywhere in this estate. Two amounts in different currencies
 * are not comparable and cannot be combined, and an attempt to do so is a defect in the caller
 * rather than a case to be handled. FX belongs to {@code payment-engine}, which carries an explicit
 * rate and an audit trail.
 */
public class CurrencyMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CurrencyMismatchException(CurrencyCode left, CurrencyCode right) {
        super("Cannot combine amounts in " + left + " and " + right
                + ": there is no implicit conversion in this ledger.");
    }
}
