package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.domain.HoldStatus;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.port.HoldRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** The hold port, against PostgreSQL. */
public final class JdbcHoldRepository implements HoldRepository {

    private static final String COLUMNS =
            "reference, account_ref, amount_minor, currency, status, placed_at, expires_at,"
                    + " transitioned_at, captured_by";

    /**
     * Which statuses still reduce available balance, taken from the enum rather than restated.
     *
     * <p>Writing {@code status = 'PLACED'} in the SQL would work today and be wrong the day a fifth
     * status is added that is also active. {@link HoldStatus#isActive()} is the authority.
     */
    private static final List<String> ACTIVE_STATUSES = Arrays.stream(HoldStatus.values())
            .filter(HoldStatus::isActive)
            .map(HoldStatus::name)
            .toList();

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcHoldRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Hold save(Hold hold) {
        jdbc.update(
                """
                INSERT INTO hold
                    (reference, account_ref, amount_minor, currency, status, placed_at, expires_at,
                     transitioned_at, captured_by)
                VALUES
                    (:reference, :account, :amount, :currency, :status, :placedAt, :expiresAt,
                     :transitionedAt, :capturedBy)
                ON CONFLICT (reference) DO UPDATE SET
                    status          = EXCLUDED.status,
                    transitioned_at = EXCLUDED.transitioned_at,
                    captured_by     = EXCLUDED.captured_by
                """,
                new MapSqlParameterSource()
                        .addValue("reference", hold.reference().value())
                        .addValue("account", hold.account().value())
                        .addValue("amount", hold.amount().amountMinor())
                        .addValue("currency", hold.amount().currency().code())
                        .addValue("status", hold.status().name())
                        .addValue("placedAt", OffsetDateTime.ofInstant(hold.placedAt(), java.time.ZoneOffset.UTC))
                        .addValue(
                                "expiresAt",
                                hold.expiresAt()
                                        .map(at -> OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
                                        .orElse(null))
                        .addValue(
                                "transitionedAt",
                                hold.transitionedAt()
                                        .map(at -> OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC))
                                        .orElse(null))
                        .addValue("capturedBy", hold.capturedBy().map(EntryRef::value).orElse(null)));
        return hold;
    }

    @Override
    public Optional<Hold> findByReference(HoldRef reference) {
        List<Hold> found = jdbc.query(
                "SELECT " + COLUMNS + " FROM hold WHERE reference = :reference",
                Map.of("reference", reference.value()),
                MAPPER);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<Hold> findActiveFor(AccountRef account) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM hold"
                        + " WHERE account_ref = :account AND status IN (:statuses)"
                        + " ORDER BY placed_at, reference",
                Map.of("account", account.value(), "statuses", ACTIVE_STATUSES),
                MAPPER);
    }

    @Override
    public List<Hold> findAllFor(AccountRef account) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM hold"
                        + " WHERE account_ref = :account"
                        + " ORDER BY placed_at, reference",
                Map.of("account", account.value()),
                MAPPER);
    }

    private static final RowMapper<Hold> MAPPER = JdbcHoldRepository::mapHold;

    /**
     * Rebuilds a hold through {@code place} and then its recorded transition.
     *
     * <p>{@link Hold} exposes no reconstruction factory, deliberately - it is an immutable aggregate
     * with a lifecycle, and a persistence-shaped back door into it is how a lifecycle stops being
     * enforced. So a captured or released hold is placed and then transitioned.
     *
     * <p>The transition instant comes from the row. It used to come from {@code placed_at}, because
     * {@code transitionTo} discarded whatever it was given (follow-up F-21) and there was no honest
     * value to pass. Now that the aggregate keeps it, the column holds it and a test asserts it
     * survives the round trip - which is the difference between a value that cannot matter and one
     * that does.
     */
    private static Hold mapHold(ResultSet row, int rowNumber) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency"));
        Instant placedAt = row.getObject("placed_at", OffsetDateTime.class).toInstant();
        OffsetDateTime expires = row.getObject("expires_at", OffsetDateTime.class);

        Hold placed = Hold.place(
                HoldRef.of(row.getString("reference")),
                AccountRef.of(row.getString("account_ref")),
                Money.of(row.getLong("amount_minor"), currency),
                placedAt,
                expires == null ? null : expires.toInstant());

        HoldStatus status = HoldStatus.valueOf(row.getString("status"));
        String capturedBy = row.getString("captured_by");
        OffsetDateTime transitioned = row.getObject("transitioned_at", OffsetDateTime.class);
        Instant transitionedAt = transitioned == null ? null : transitioned.toInstant();
        return switch (status) {
            case PLACED -> placed;
            case CAPTURED -> placed.capture(EntryRef.of(capturedBy), transitionedAt);
            case RELEASED -> placed.release(transitionedAt);
            case EXPIRED -> placed.expire(transitionedAt);
        };
    }
}
