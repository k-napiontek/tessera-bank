package bank.tessera.ledger.domain;

/**
 * Which side of the books a posting falls on.
 *
 * <p>Direction carries the sign. A posting amount is always positive; whether it increases or
 * decreases an account is a function of this and the account's type. Storing signed amounts instead
 * would work until someone posted a negative debit and nobody noticed.
 *
 * <p>Traces to {@code Movement.direction} in docs/architecture/canonical-data-model.md, which the
 * copybook encodes as {@code 'D'} and {@code 'C'}.
 */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
