package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.Balance;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.Posting;
import bank.tessera.ledger.port.HoldRepository;
import bank.tessera.ledger.port.JournalEntryRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The journal entry port, against PostgreSQL.
 *
 * <p><strong>Append-only, and not only by convention.</strong> There is no update and no delete here
 * because the port offers neither, and the schema refuses both with a trigger for everything that is
 * not this class. A correction is a reversing entry.
 *
 * <p>A balance is read one way and verified another, deliberately. {@link #balanceOf} reads the
 * materialised {@code balance} row - the fast path an API call takes - while
 * {@link BalanceReconciliation} sums the postings. Two independent derivations that must agree is what
 * makes the materialised figure trustworthy; if this method summed the postings itself, the
 * reconciliation would compare a number to itself and could never fail.
 */
public final class JdbcJournalEntryRepository implements JournalEntryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final HoldRepository holds;
    private final TransactionTemplate transactions;

    public JdbcJournalEntryRepository(
            NamedParameterJdbcTemplate jdbc, HoldRepository holds, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.holds = holds;
        this.transactions = transactions;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One transaction: the entry, its postings, and every affected balance. A partially applied entry
     * would leave the ledger unbalanced, which is the one state it must never reach.
     */
    @Override
    public JournalEntry append(JournalEntry entry) {
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    INSERT INTO journal_entry (reference, value_date, currency, reverses)
                    VALUES (:reference, :valueDate, :currency, :reverses)
                    """,
                    new MapSqlParameterSource()
                            .addValue("reference", entry.reference().value())
                            .addValue("valueDate", entry.valueDate())
                            .addValue("currency", entry.currency().code())
                            // WP-06 has carried reverses() since the domain was written and WP-07's
                            // schema had nowhere to put it, so a reversal round-tripped losing the
                            // link to the entry it corrected. The unique index on this column is
                            // what stops one transfer being reversed twice.
                            .addValue("reverses", entry.reverses().map(EntryRef::value).orElse(null)));

            List<Posting> postings = entry.postings();
            for (int index = 0; index < postings.size(); index++) {
                Posting posting = postings.get(index);
                jdbc.update(
                        """
                        INSERT INTO posting
                            (entry_ref, seq, account_ref, direction, amount_minor, currency)
                        VALUES
                            (:entry, :seq, :account, :direction, :amount, :currency)
                        """,
                        new MapSqlParameterSource()
                                // seq preserves the order of the list. A table has no order of its own,
                                // and leg 1 of a transfer must read back as leg 1.
                                .addValue("entry", entry.reference().value())
                                .addValue("seq", index + 1)
                                .addValue("account", posting.account().value())
                                .addValue("direction", posting.direction().name())
                                .addValue("amount", posting.amount().amountMinor())
                                .addValue("currency", posting.amount().currency().code()));
            }

            applyToBalances(entry);
        });
        return entry;
    }

    /**
     * Moves each affected balance by the posting's signed effect.
     *
     * <p>The sign comes from {@link AccountType#signedEffect}, never from a copy of the rule written
     * here. Whether a debit raises or lowers an account is domain knowledge, and a second copy of it in
     * an adapter is a second copy to get wrong - which is precisely the defect that survives review
     * because both copies look reasonable.
     *
     * <p>The updates are applied in account order. Each one takes a row lock, so two concurrent entries
     * touching the same pair of accounts in opposite orders would deadlock; sorting removes the cycle.
     * {@link AccountLocks} states the same rule for explicit locking.
     */
    private void applyToBalances(JournalEntry entry) {
        Map<AccountRef, AccountType> types = typesOf(entry);
        Map<String, Long> deltasByAccount = new TreeMap<>();

        for (Posting posting : entry.postings()) {
            AccountType type = types.get(posting.account());
            if (type == null) {
                throw new IllegalStateException(
                        "No account " + posting.account() + " for posting in entry " + entry.reference());
            }
            Money effect = type.signedEffect(posting.direction(), posting.amount());
            deltasByAccount.merge(posting.account().value(), effect.amountMinor(), Long::sum);
        }

        for (Map.Entry<String, Long> delta : deltasByAccount.entrySet()) {
            int updated = jdbc.update(
                    """
                    UPDATE balance
                       SET booked_minor = booked_minor + :delta,
                           updated_at   = now()
                     WHERE account_ref = :account
                    """,
                    new MapSqlParameterSource()
                            .addValue("delta", delta.getValue())
                            .addValue("account", delta.getKey()));
            if (updated != 1) {
                throw new IllegalStateException(
                        "No balance row for account " + delta.getKey()
                                + "; an account is opened with its balance, so this row is missing.");
            }
        }
    }

    private Map<AccountRef, AccountType> typesOf(JournalEntry entry) {
        List<String> references = entry.postings().stream()
                .map(posting -> posting.account().value())
                .distinct()
                .toList();

        Map<AccountRef, AccountType> types = new HashMap<>();
        jdbc.query(
                "SELECT reference, account_type FROM account WHERE reference IN (:references)",
                Map.of("references", references),
                row -> {
                    types.put(
                            AccountRef.of(row.getString("reference")),
                            AccountType.valueOf(row.getString("account_type")));
                });
        return types;
    }

    @Override
    public Optional<JournalEntry> findByReference(EntryRef reference) {
        List<JournalEntry> found = loadEntries(List.of(reference.value()));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<JournalEntry> findByAccount(AccountRef account, LocalDate from, LocalDate to) {
        List<String> references = jdbc.queryForList(
                """
                SELECT e.reference
                  FROM journal_entry e
                 WHERE e.value_date BETWEEN :from AND :to
                   AND EXISTS (SELECT 1 FROM posting p
                                WHERE p.entry_ref = e.reference
                                  AND p.account_ref = :account)
                 ORDER BY e.value_date, e.reference
                """,
                new MapSqlParameterSource()
                        .addValue("account", account.value())
                        .addValue("from", from)
                        .addValue("to", to),
                String.class);
        return loadEntries(references);
    }

    /**
     * Loads whole entries, including the legs on accounts the caller did not ask about.
     *
     * <p>A {@link JournalEntry} must balance to exist, so returning one with only the postings that
     * touch the requested account would fail its own invariant - and rightly: half an entry is not a
     * smaller entry, it is a broken one.
     */
    private List<JournalEntry> loadEntries(List<String> references) {
        if (references.isEmpty()) {
            return List.of();
        }

        Map<String, List<Posting>> postingsByEntry = new LinkedHashMap<>();
        Map<String, LocalDate> valueDates = new LinkedHashMap<>();
        Map<String, String> reversedByEntry = new LinkedHashMap<>();

        jdbc.query(
                """
                SELECT e.reference, e.value_date, e.reverses, p.seq, p.account_ref, p.direction,
                       p.amount_minor, p.currency
                  FROM journal_entry e
                  JOIN posting p ON p.entry_ref = e.reference
                 WHERE e.reference IN (:references)
                 ORDER BY e.value_date, e.reference, p.seq
                """,
                Map.of("references", references),
                row -> {
                    String entry = row.getString("reference");
                    valueDates.putIfAbsent(entry, row.getDate("value_date").toLocalDate());
                    String reverses = row.getString("reverses");
                    if (reverses != null) {
                        reversedByEntry.putIfAbsent(entry, reverses);
                    }
                    postingsByEntry
                            .computeIfAbsent(entry, key -> new ArrayList<>())
                            .add(Posting.of(
                                    AccountRef.of(row.getString("account_ref")),
                                    Direction.valueOf(row.getString("direction")),
                                    Money.of(
                                            row.getLong("amount_minor"),
                                            CurrencyCode.of(row.getString("currency")))));
                });

        List<JournalEntry> entries = new ArrayList<>(postingsByEntry.size());
        postingsByEntry.forEach((reference, postings) -> entries.add(
                rebuild(EntryRef.of(reference), valueDates.get(reference), postings,
                        reversedByEntry.get(reference))));
        return entries;
    }

    /**
     * Rebuilds an entry, going through {@code reverse} when it is a reversal.
     *
     * <p>{@link JournalEntry} offers no way to set {@code reverses} except by reversing the entry it
     * corrects - deliberately, since a reversal that does not descend from its original cannot be
     * trusted to be its opposite. So the original is loaded and reversed, exactly as the hold adapter
     * rebuilds a captured hold by placing it and applying the recorded transition.
     *
     * <p>The rebuilt postings are then checked against the ones actually stored. They must match: the
     * reversal was written as the flip of its original, and if the two ever disagree the database has
     * a correction that does not correct what it names. Trusting the computed version and discarding
     * the stored one would hide exactly that.
     */
    private JournalEntry rebuild(
            EntryRef reference, LocalDate valueDate, List<Posting> stored, String reverses) {
        if (reverses == null) {
            return JournalEntry.of(reference, valueDate, stored);
        }
        JournalEntry original = findByReference(EntryRef.of(reverses))
                .orElseThrow(() -> new IllegalStateException(
                        "Entry " + reference + " reverses " + reverses + ", which does not exist."));
        JournalEntry rebuilt = original.reverse(reference, valueDate);
        if (!rebuilt.postings().equals(stored)) {
            throw new IllegalStateException(
                    "Entry " + reference + " is stored as a reversal of " + reverses
                            + ", but its postings are not that entry's opposite.");
        }
        return rebuilt;
    }

    @Override
    public Balance balanceOf(AccountRef account) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT booked_minor, currency FROM balance WHERE account_ref = :account",
                Map.of("account", account.value()));

        Money booked = Money.of(
                ((Number) row.get("booked_minor")).longValue(),
                CurrencyCode.of(((String) row.get("currency")).trim()));

        return Balance.of(account, booked, holds.findActiveFor(account));
    }
}
