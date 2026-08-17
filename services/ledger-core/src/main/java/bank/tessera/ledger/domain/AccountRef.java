package bank.tessera.ledger.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The estate-wide account key: sixteen characters, {@code TB} then fourteen alphanumerics.
 *
 * <p>Validated on construction, so a malformed reference cannot enter the domain at all. The pattern
 * is the one in docs/architecture/canonical-data-model.md, and it is shared with the copybook, the
 * XSD, the OpenAPI document and the AsyncAPI document.
 *
 * <p>Pseudonymous, and therefore still personal data under GDPR. It may be logged; it is never
 * resolved to a person outside {@code customer-master}.
 */
public final class AccountRef {

    private static final Pattern PATTERN = Pattern.compile("^TB[0-9A-Z]{14}$");

    private final String value;

    private AccountRef(String value) {
        this.value = value;
    }

    public static AccountRef of(String value) {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Account reference must match " + PATTERN.pattern() + ", but was: " + value);
        }
        return new AccountRef(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AccountRef that && value.equals(that.value);
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
