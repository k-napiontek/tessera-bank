package bank.tessera.ledger.domain;

import java.util.Objects;

/**
 * How far below zero an account is permitted to go.
 *
 * <p>Most retail current accounts forbid it outright; some carry an agreed limit. Expressing the
 * limit as money rather than a boolean means the arranged-overdraft case needs no second concept
 * later, and the check reads the same either way.
 *
 * <p>Immutable. The limit is held as a positive amount and compared against a balance that may be
 * negative, which keeps "how much overdraft" and "how negative is the balance" from being confused.
 */
public final class OverdraftPolicy {

    private final Money limit;

    private OverdraftPolicy(Money limit) {
        this.limit = limit;
    }

    /** No overdraft at all: the balance may reach zero and go no further. */
    public static OverdraftPolicy forbidden() {
        return new OverdraftPolicy(null);
    }

    /**
     * An arranged overdraft of {@code limit}.
     *
     * @param limit a non-negative amount; a negative limit is a contradiction, not a bigger overdraft
     */
    public static OverdraftPolicy upTo(Money limit) {
        Objects.requireNonNull(limit, "limit");
        if (limit.isNegative()) {
            throw new IllegalArgumentException(
                    "An overdraft limit is a positive allowance, but was: " + limit);
        }
        return new OverdraftPolicy(limit);
    }

    public boolean isForbidden() {
        return limit == null;
    }

    /** The arranged limit, or zero when no overdraft is permitted. */
    public Money limitOr(CurrencyCode currency) {
        return limit == null ? Money.zero(currency) : limit;
    }

    /** Whether {@code balance} is within this policy. */
    public boolean permits(Money balance) {
        Objects.requireNonNull(balance, "balance");
        if (!balance.isNegative()) {
            return true;
        }
        return limit != null && balance.negate().compareTo(limit) <= 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OverdraftPolicy that && Objects.equals(limit, that.limit);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(limit);
    }

    @Override
    public String toString() {
        return limit == null ? "no overdraft" : "overdraft up to " + limit;
    }
}
