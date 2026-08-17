package bank.tessera.ledger.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** A hold reference: {@code HL}, then {@code CCYYMMDD}, then a ten-digit sequence. */
public final class HoldRef {

    private static final Pattern PATTERN = Pattern.compile("^HL[0-9]{18}$");

    private final String value;

    private HoldRef(String value) {
        this.value = value;
    }

    public static HoldRef of(String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Hold reference must match " + PATTERN.pattern() + ", but was: " + value);
        }
        return new HoldRef(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HoldRef that && value.equals(that.value);
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
