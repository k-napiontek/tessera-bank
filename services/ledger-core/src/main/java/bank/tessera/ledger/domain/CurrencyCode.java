package bank.tessera.ledger.domain;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An ISO 4217 currency, with the scale that gives its minor units meaning.
 *
 * <p>The scale table is transcribed from {@code docs/architecture/canonical-data-model.md}, which is
 * the authority. It deliberately carries currencies of scale 0 and 3 as well as 2, so that code
 * assuming "two decimals everywhere" fails a test rather than passing quietly and being discovered
 * in production by a Japanese customer.
 *
 * <p>A currency absent from the table is rejected, never guessed. Defaulting an unknown code to two
 * decimals is how a ledger silently misstates money.
 */
public final class CurrencyCode {

    private static final Pattern ALPHA_3 = Pattern.compile("^[A-Z]{3}$");

    /** ISO 4217 minor-unit scale, per docs/architecture/canonical-data-model.md section 2. */
    private static final Map<String, Integer> SCALES = Map.of(
            "PLN", 2,
            "EUR", 2,
            "USD", 2,
            "GBP", 2,
            "CHF", 2,
            "JPY", 0,
            "KRW", 0,
            "BHD", 3,
            "KWD", 3,
            "TND", 3);

    private final String code;
    private final int scale;

    private CurrencyCode(String code, int scale) {
        this.code = code;
        this.scale = scale;
    }

    public static CurrencyCode of(String code) {
        if (code == null || !ALPHA_3.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "Currency must be an ISO 4217 alpha-3 code in upper case, but was: " + code);
        }
        Integer scale = SCALES.get(code);
        if (scale == null) {
            throw new IllegalArgumentException(
                    "Unsupported currency: " + code
                            + ". Add it to the ISO 4217 scale table in the canonical data model first"
                            + " - an unknown scale must never be assumed.");
        }
        return new CurrencyCode(code, scale);
    }

    public String code() {
        return code;
    }

    /** Number of decimal places, from ISO 4217. Zero for JPY, three for BHD. */
    public int scale() {
        return scale;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CurrencyCode that && code.equals(that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(code);
    }

    @Override
    public String toString() {
        return code;
    }
}
