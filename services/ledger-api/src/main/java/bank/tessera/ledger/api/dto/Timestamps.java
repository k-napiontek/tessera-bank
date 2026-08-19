package bank.tessera.ledger.api.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The one rendering of a timestamp this API emits.
 *
 * <p>The canonical data model fixes it: RFC 3339, millisecond precision, {@code Z}, and no other
 * offset anywhere in the estate. Jackson's default for {@link Instant} varies its precision with the
 * value - a whole second renders without a fraction, a microsecond renders with six digits - so two
 * responses describing the same kind of field would carry different shapes and a contract test would
 * be asserting the clock rather than the code.
 *
 * <p>Dates need no such treatment: {@code format: date} has exactly one ISO rendering, so
 * {@link java.time.LocalDate} is carried as itself.
 */
public final class Timestamps {

    private static final DateTimeFormatter RFC_3339_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Timestamps() {}

    public static String format(Instant instant) {
        return instant == null ? null : RFC_3339_MILLIS.format(instant);
    }
}
