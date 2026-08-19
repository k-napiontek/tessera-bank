package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.port.AccountDates;
import bank.tessera.ledger.port.LedgerReadModel;
import bank.tessera.ledger.port.Movement;
import bank.tessera.ledger.port.StatementPage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
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

    @Override
    public Optional<String> entryReference(EntryRef entry) {
        List<String> found = jdbc.query(
                "SELECT reference_text FROM journal_entry WHERE reference = :entry",
                Map.of("entry", entry.value()),
                (row, rowNumber) -> row.getString("reference_text"));
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    @Override
    public void recordEntryReference(EntryRef entry, String reference) {
        int updated = jdbc.update(
                "UPDATE journal_entry SET reference_text = :reference WHERE reference = :entry",
                new MapSqlParameterSource()
                        .addValue("reference", reference)
                        .addValue("entry", entry.value()));
        if (updated != 1) {
            throw new IllegalStateException(
                    "Cannot attach remittance information to entry " + entry + ": it does not exist.");
        }
    }

    @Override
    public StatementPage statementPage(
            AccountRef account, LocalDate from, LocalDate to, String cursor, int limit) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (limit < 1) {
            throw new IllegalArgumentException("Statement page limit must be positive, but was: " + limit);
        }
        StatementCursor resume = cursor == null ? null : StatementCursor.decode(cursor);
        CurrencyCode currency = currencyOf(account);

        // One row more than asked for. Its presence is what says there is a next page - counting the
        // remainder with a second query would answer a question about a moment that has already
        // passed, and could report a next page that no longer exists.
        List<Row> rows = fetch(account, from, to, resume, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<Row> page = hasMore ? rows.subList(0, limit) : rows;

        Money opening = openingBalance(account, currency, from, resume, page);
        String next = hasMore ? page.get(page.size() - 1).cursor().encode() : null;

        return StatementPage.of(page.stream().map(Row::movement).toList(), opening, next);
    }

    private CurrencyCode currencyOf(AccountRef account) {
        List<String> found = jdbc.query(
                "SELECT currency FROM account WHERE reference = :account",
                Map.of("account", account.value()),
                (row, rowNumber) -> row.getString("currency"));
        if (found.isEmpty()) {
            throw new IllegalStateException("No such account: " + account);
        }
        return CurrencyCode.of(found.get(0));
    }

    private List<Row> fetch(
            AccountRef account, LocalDate from, LocalDate to, StatementCursor resume, int limit) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("account", account.value())
                .addValue("from", from)
                .addValue("to", to)
                .addValue("limit", limit);

        String keyset = "";
        if (resume != null) {
            // Row-wise comparison, not a chain of ORs. PostgreSQL compares the tuples left to right
            // in exactly the order the index would, and a hand-written expansion of it is where an
            // off-by-one at a page boundary hides.
            keyset = " AND (e.value_date, e.created_at, p.entry_ref, p.seq)"
                    + " > (:cursorValueDate, :cursorPostedAt, :cursorEntry, :cursorSeq)";
            bindCursor(parameters, resume);
        }

        return jdbc.query(
                "SELECT e.reference AS entry_ref, p.seq, p.account_ref, p.direction, p.amount_minor,"
                        + " p.currency, e.value_date, e.created_at, e.reference_text"
                        + " FROM posting p"
                        + " JOIN journal_entry e ON e.reference = p.entry_ref"
                        + " WHERE p.account_ref = :account"
                        + " AND e.value_date BETWEEN :from AND :to"
                        + keyset
                        + " ORDER BY e.value_date, e.created_at, p.entry_ref, p.seq"
                        + " LIMIT :limit",
                parameters,
                ROW_MAPPER);
    }

    /**
     * The booked balance immediately before the page starts.
     *
     * <p>Cumulative from the account's first posting, not from the start of the range: a statement
     * for March opens at whatever February left behind. The boundary is the first movement on the
     * page when there is one; on an empty page it is the cursor, or the start of the range when there
     * is no cursor either.
     */
    private Money openingBalance(
            AccountRef account,
            CurrencyCode currency,
            LocalDate from,
            StatementCursor resume,
            List<Row> page) {
        MapSqlParameterSource parameters =
                new MapSqlParameterSource().addValue("account", account.value());
        String boundary;

        if (!page.isEmpty()) {
            boundary = " AND (e.value_date, e.created_at, p.entry_ref, p.seq)"
                    + " < (:cursorValueDate, :cursorPostedAt, :cursorEntry, :cursorSeq)";
            bindCursor(parameters, page.get(0).cursor());
        } else if (resume != null) {
            boundary = " AND (e.value_date, e.created_at, p.entry_ref, p.seq)"
                    + " <= (:cursorValueDate, :cursorPostedAt, :cursorEntry, :cursorSeq)";
            bindCursor(parameters, resume);
        } else {
            boundary = " AND e.value_date < :from";
            parameters.addValue("from", from);
        }

        Long summed = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN (a.account_type IN ('ASSET', 'EXPENSE'))"
                        + "                       = (p.direction = 'DEBIT')"
                        + "                  THEN p.amount_minor ELSE -p.amount_minor END), 0)"
                        + " FROM posting p"
                        + " JOIN journal_entry e ON e.reference = p.entry_ref"
                        + " JOIN account a ON a.reference = p.account_ref"
                        + " WHERE p.account_ref = :account"
                        + boundary,
                parameters,
                Long.class);
        return Money.of(summed == null ? 0L : summed, currency);
    }

    private static void bindCursor(MapSqlParameterSource parameters, StatementCursor cursor) {
        parameters
                .addValue("cursorValueDate", cursor.valueDate())
                .addValue("cursorPostedAt", OffsetDateTime.ofInstant(cursor.postedAt(), ZoneOffset.UTC))
                .addValue("cursorEntry", cursor.entryReference())
                .addValue("cursorSeq", cursor.seq());
    }

    private static final RowMapper<Row> ROW_MAPPER = (row, rowNumber) -> {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency"));
        Instant postedAt = row.getObject("created_at", OffsetDateTime.class).toInstant();
        LocalDate valueDate = row.getObject("value_date", LocalDate.class);
        String entryReference = row.getString("entry_ref");
        int seq = row.getInt("seq");

        Movement movement = Movement.of(
                EntryRef.of(entryReference),
                seq,
                AccountRef.of(row.getString("account_ref")),
                Direction.valueOf(row.getString("direction")),
                Money.of(row.getLong("amount_minor"), currency),
                valueDate,
                postedAt,
                row.getString("reference_text"));
        return new Row(movement, new StatementCursor(valueDate, postedAt, entryReference, seq));
    };

    /** A movement and the cursor that names it, so the page never recomputes its own sort key. */
    private record Row(Movement movement, StatementCursor cursor) {}
}
