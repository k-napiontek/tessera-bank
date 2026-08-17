package bank.tessera.ledger.domain;

import java.util.Collection;
import java.util.Objects;

/**
 * What an account contains, and how much of it can actually be spent.
 *
 * <p>Two figures, and the difference between them matters:
 *
 * <ul>
 *   <li><strong>booked</strong> - the settled position, the sum of every posting;
 *   <li><strong>available</strong> - booked less every hold still {@code PLACED}.
 * </ul>
 *
 * <p>Available is <em>derived</em>, never stored. Storing it would create a second source of truth
 * that drifts the first time a hold is captured without recomputing, and a customer would be told
 * they can spend money they cannot.
 *
 * <p>Available may legitimately go negative when holds exceed the booked balance. It reports that
 * honestly rather than flooring at zero, because a floored figure hides the condition that caused it.
 */
public final class Balance {

    private final AccountRef account;
    private final Money booked;
    private final Money available;

    private Balance(AccountRef account, Money booked, Money available) {
        this.account = account;
        this.booked = booked;
        this.available = available;
    }

    /**
     * @param holds every hold on the account; those not {@code PLACED} are ignored
     */
    public static Balance of(AccountRef account, Money booked, Collection<Hold> holds) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(booked, "booked");
        Objects.requireNonNull(holds, "holds");

        Money reserved = Money.zero(booked.currency());
        for (Hold hold : holds) {
            if (hold.isActive()) {
                // Money.minus refuses to mix currencies, so a hold in the wrong currency fails here
                // rather than silently distorting the available balance.
                reserved = reserved.plus(hold.amount());
            }
        }
        return new Balance(account, booked, booked.minus(reserved));
    }

    public AccountRef account() {
        return account;
    }

    /** The settled position: the sum of every posting. */
    public Money booked() {
        return booked;
    }

    /** Booked less every active hold. May be negative when holds exceed the balance. */
    public Money available() {
        return available;
    }

    public CurrencyCode currency() {
        return booked.currency();
    }

    /**
     * The balance after applying a signed effect, subject to the account's overdraft policy.
     *
     * <p>This is where WP-06 invariant 5 lives: an account whose policy forbids an overdraft cannot
     * be taken below zero. Credits are never blocked - refusing money coming in would be a defect,
     * not a control.
     *
     * @param effect the signed effect of a posting, from {@link AccountType#signedEffect}
     * @throws OverdraftNotPermittedException if the resulting booked balance breaches the policy
     */
    public Balance afterEffect(Money effect, OverdraftPolicy policy) {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(policy, "policy");

        Money resulting = booked.plus(effect);

        // Money coming in is never blocked, even into an account that is already overdrawn beyond
        // its limit - refusing a repayment because the balance is too negative would be absurd, and
        // an account can arrive in that state legitimately through fees or a reduced limit. Only an
        // effect that makes the position worse is subject to the policy.
        if (effect.isNegative() && !policy.permits(resulting)) {
            throw new OverdraftNotPermittedException(resulting, policy);
        }
        return new Balance(account, resulting, available.plus(effect));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Balance that
                && account.equals(that.account)
                && booked.equals(that.booked)
                && available.equals(that.available);
    }

    @Override
    public int hashCode() {
        return Objects.hash(account, booked, available);
    }

    @Override
    public String toString() {
        return "Balance[" + account + " booked " + booked + ", available " + available + "]";
    }
}
