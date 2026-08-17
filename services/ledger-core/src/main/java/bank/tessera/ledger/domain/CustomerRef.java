package bank.tessera.ledger.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A pseudonymous link to {@code customer-master}: {@code CU} then ten digits.
 *
 * <p>This is the only thing the ledger knows about a customer, and deliberately so. The join to a
 * person happens in {@code customer-master}, which keeps most of the estate out of scope for
 * personal data entirely. There is no name here, no address and no national identifier, and none may
 * be added.
 */
public final class CustomerRef {

    private static final Pattern PATTERN = Pattern.compile("^CU[0-9]{10}$");

    private final String value;

    private CustomerRef(String value) {
        this.value = value;
    }

    public static CustomerRef of(String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Customer reference must match " + PATTERN.pattern() + ", but was: " + value);
        }
        return new CustomerRef(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CustomerRef that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
