package bank.tessera.customer.domain;

/**
 * An amount and its currency, which are never separated.
 *
 * <p>A signed count of MINOR UNITS. 123456789 with PLN is 1 234 567.89. Never a double, never a
 * BigDecimal carrying a scale of its own - the scale belongs to the currency, and the canonical
 * model's table is what resolves it. Two amounts are comparable only when their currencies are
 * equal, and there is no implicit conversion anywhere in this estate.
 *
 * <p>JavaBean accessors throughout this tier, deliberately. Stratum 3 writes {@code amountMinor()};
 * a 2011 Java EE codebase wrote {@code getAmountMinor()}, and JAXB binds to it. A reader should be
 * able to date this code from its style alone.
 */
public final class Money {

    private final long amountMinor;
    private final String currency;

    public Money(long amountMinor, String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("not an ISO 4217 alpha-3 code: " + currency);
        }
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Money)) {
            return false;
        }
        Money that = (Money) other;
        return amountMinor == that.amountMinor && currency.equals(that.currency);
    }

    public int hashCode() {
        return (int) (amountMinor ^ (amountMinor >>> 32)) * 31 + currency.hashCode();
    }

    public String toString() {
        return amountMinor + " " + currency;
    }
}
