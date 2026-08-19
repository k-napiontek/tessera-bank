package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.port.ReferenceGenerator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Reference allocation, against PostgreSQL sequences.
 *
 * <p>A reference is the two-letter kind, then {@code CCYYMMDD}, then a ten-digit sequence, exactly
 * as the canonical data model fixes it. The date is the business date; the sequence is global rather
 * than per-day, because the date already separates one day's references from the next and a
 * per-day counter would need a row to reset, which is a lock every transfer would queue behind.
 *
 * <p>{@code nextval} is deliberately not transactional. A rolled-back transfer consumes a reference
 * and leaves a gap in the series - correct behaviour, since the alternative is serialising every
 * allocation. A gap in a reference series is not a missing transfer, and any ledger that gets this
 * wrong gets it wrong by being slow rather than by being safe.
 */
public final class JdbcReferenceGenerator implements ReferenceGenerator {

    private static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public JdbcReferenceGenerator(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public EntryRef nextEntryReference() {
        return EntryRef.of("TB" + today() + next("entry_reference_seq"));
    }

    @Override
    public HoldRef nextHoldReference() {
        return HoldRef.of("HL" + today() + next("hold_reference_seq"));
    }

    private String today() {
        return LocalDate.now(clock).format(BUSINESS_DATE);
    }

    private String next(String sequence) {
        // The sequence name cannot be a bind parameter, so it is interpolated - and it is a constant
        // of this class, never anything a caller supplied.
        Long value = jdbc.queryForObject("SELECT nextval('" + sequence + "')", Map.of(), Long.class);
        if (value == null) {
            throw new IllegalStateException("Sequence " + sequence + " returned no value.");
        }
        return String.format("%010d", value);
    }
}
