package bank.tessera.ledger.domain;

import java.util.Objects;

/**
 * One leg of a double-entry posting: an amount, a direction, and the account it lands on.
 *
 * <p>The amount is <strong>always strictly positive</strong>. Direction carries the sign. Permitting
 * a negative debit would give every amount two representations and quietly break the balancing
 * check, because a negative debit and a positive credit would both appear on the debit side.
 *
 * <p>Immutable, and append-only by construction: there is no setter and no mutating method. A
 * mistake is corrected by a reversing entry, never by changing this.
 *
 * <p>The rest of the estate calls this a {@code Movement}; see
 * docs/architecture/canonical-data-model.md.
 */
public final class Posting {

    private final AccountRef account;
    private final Direction direction;
    private final Money amount;

    private Posting(AccountRef account, Direction direction, Money amount) {
        this.account = account;
        this.direction = direction;
        this.amount = amount;
    }

    public static Posting of(AccountRef account, Direction direction, Money amount) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "A posting amount must be strictly positive - direction carries the sign - but was: "
                            + amount);
        }
        return new Posting(account, direction, amount);
    }

    public AccountRef account() {
        return account;
    }

    public Direction direction() {
        return direction;
    }

    public Money amount() {
        return amount;
    }

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }

    /** The same posting on the opposite side, for building a reversal. */
    public Posting reversed() {
        return new Posting(account, direction.opposite(), amount);
    }

    /** The signed effect of this posting on an account of the given type. */
    public Money effectOn(AccountType type) {
        return type.signedEffect(direction, amount);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Posting that
                && account.equals(that.account)
                && direction == that.direction
                && amount.equals(that.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(account, direction, amount);
    }

    @Override
    public String toString() {
        return direction + " " + amount + " " + account;
    }
}
