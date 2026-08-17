package bank.tessera.ledger.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The estate-wide reference for a journal entry: {@code TB}, then {@code CCYYMMDD}, then a ten-digit
 * sequence.
 *
 * <p>The same value the rest of the estate calls {@code transferRef}. A transfer is the customer's
 * intent; the journal entry is its accounting form, and they share one reference so the two views
 * can never disagree about which is which.
 */
public final class EntryRef {

    private static final Pattern PATTERN = Pattern.compile("^TB[0-9]{18}$");

    private final String value;

    private EntryRef(String value) {
        this.value = value;
    }

    public static EntryRef of(String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Entry reference must match " + PATTERN.pattern() + ", but was: " + value);
        }
        return new EntryRef(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EntryRef that && value.equals(that.value);
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
