package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.port.AccountDates;
import bank.tessera.ledger.port.LedgerReadModel;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The read model, against PostgreSQL.
 *
 * <p>Every figure here is read from the rows the repositories already write. Nothing is materialised
 * into a second table: {@code lastMovementDate} is a {@code MAX} over the account's postings rather
 * than a column kept in step by hand, because a maintained copy is a second source of truth and this
 * ledger already runs a reconciliation to catch the one it does keep.
 */
public final class JdbcLedgerReadModel implements LedgerReadModel {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLedgerReadModel(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<AccountDates> accountDates(AccountRef account) {
        List<AccountDates> found = jdbc.query(
                """
                SELECT a.opened_date,
                       (SELECT MAX(e.value_date)
                          FROM posting p
                          JOIN journal_entry e ON e.reference = p.entry_ref
                         WHERE p.account_ref = a.reference) AS last_movement_date
                  FROM account a
                 WHERE a.reference = :account
                """,
                Map.of("account", account.value()),
                (row, rowNumber) -> AccountDates.of(
                        row.getObject("opened_date", LocalDate.class),
                        row.getObject("last_movement_date", LocalDate.class)));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public void recordAccountOpened(AccountRef account, LocalDate openedDate) {
        int updated = jdbc.update(
                "UPDATE account SET opened_date = :openedDate WHERE reference = :account",
                new MapSqlParameterSource()
                        .addValue("openedDate", openedDate)
                        .addValue("account", account.value()));
        if (updated != 1) {
            throw new IllegalStateException(
                    "Cannot record an opening date for account " + account + ": it does not exist.");
        }
    }

    @Override
    public Optional<Instant> entryPostedAt(EntryRef entry) {
        List<Instant> found = jdbc.query(
                "SELECT created_at FROM journal_entry WHERE reference = :entry",
                Map.of("entry", entry.value()),
                (row, rowNumber) -> row.getObject("created_at", OffsetDateTime.class).toInstant());
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public Optional<EntryRef> reversedBy(EntryRef entry) {
        List<EntryRef> found = jdbc.query(
                "SELECT reference FROM journal_entry WHERE reverses = :entry",
                Map.of("entry", entry.value()),
                (row, rowNumber) -> EntryRef.of(row.getString("reference")));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }
}
