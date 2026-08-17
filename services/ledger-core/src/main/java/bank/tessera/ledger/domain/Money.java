package bank.tessera.ledger.domain;

import java.util.Objects;

/**
 * An exact amount of money: a count of minor units, plus the currency that gives them scale.
 *
 * <p><strong>Money is never a floating-point number.</strong> Not a {@code double}, not a
 * {@code float}, and not a {@code BigDecimal} in storage or arithmetic. {@code 123456789} with
 * currency {@code PLN} is 1 234 567.89; the decimal point is applied at a presentation boundary and
 * nowhere else.
 *
 * <p>Immutable. Every operation returns a new instance, and every operation that could overflow
 * throws instead of wrapping - a ledger that silently wraps at {@link Long#MAX_VALUE} is worse than
 * one that fails, because the failure is discoverable and the wrap is not.
 *
 * <p>Traces to {@code Money} in docs/architecture/canonical-data-model.md.
 */
public final class Money implements Comparable<Money> {

    private final long amountMinor;
    private final CurrencyCode currency;

    private Money(long amountMinor, CurrencyCode currency) {
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public static Money of(long amountMinor, CurrencyCode currency) {
        Objects.requireNonNull(currency, "currency");
        return new Money(amountMinor, currency);
    }

    public static Money zero(CurrencyCode currency) {
        return of(0L, currency);
    }

    public long amountMinor() {
        return amountMinor;
    }

    public CurrencyCode currency() {
        return currency;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(amountMinor), currency);
    }

    public Money abs() {
        return amountMinor < 0 ? negate() : this;
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    /**
     * {@inheritDoc}
     *
     * @throws CurrencyMismatchException if the currencies differ. Ordering across currencies has no
     *     meaning without a rate, so it is refused rather than approximated.
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    /**
     * The amount with its decimal point applied, for display and for nothing else.
     *
     * <p>This is the only place in the domain that knows where the decimal point goes. Never parse
     * this back into a {@code Money} - construct from minor units.
     */
    public String toPlainString() {
        int scale = currency.scale();
        if (scale == 0) {
            return Long.toString(amountMinor);
        }
        String sign = amountMinor < 0 ? "-" : "";
        String digits = Long.toString(Math.abs(amountMinor));
        if (digits.length() <= scale) {
            digits = "0".repeat(scale - digits.length() + 1) + digits;
        }
        int split = digits.length() - scale;
        return sign + digits.substring(0, split) + "." + digits.substring(split);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Money that
                && amountMinor == that.amountMinor
                && currency.equals(that.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amountMinor, currency);
    }

    @Override
    public String toString() {
        return toPlainString() + " " + currency;
    }
}
