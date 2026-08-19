package bank.tessera.ledger.port;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.EntryRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Figures the API contract requires that no aggregate holds.
 *
 * <p>{@code Account} carries no balance, no opening date and no last-movement date; {@code
 * JournalEntry} carries no posting instant and does not know whether something later reversed it.
 * That is deliberate in every case - each figure is derived, none guards an invariant, and adding
 * them to the aggregates to satisfy a response schema would put a second source of truth beside the
 * postings on the day the API shipped.
 *
 * <p>So they are read here instead, from the same rows the repositories write. This port is a read
 * model and nothing more, with one exception: {@link #recordAccountOpened} exists because
 * {@code openedDate} is a business date rather than a derived one, and something has to write it.
 * Recording it here keeps it out of {@code AccountRepository.save}, which takes an {@code Account}
 * and would otherwise need a field the aggregate has no use for.
 */
public interface LedgerReadModel {

    /** Empty when the account does not exist. */
    Optional<AccountDates> accountDates(AccountRef account);

    /** Sets the account's business opening date. Called once, when the account is opened. */
    void recordAccountOpened(AccountRef account, LocalDate openedDate);

    /** When the entry was recorded by the ledger. Empty when the entry does not exist. */
    Optional<Instant> entryPostedAt(EntryRef entry);

    /**
     * The entry that reverses {@code entry}, if one has been posted.
     *
     * <p>This is what makes a transfer's status {@code REVERSED} rather than {@code POSTED}. The
     * original is never mutated, so the only way to know is to look for the entry pointing back at
     * it.
     */
    Optional<EntryRef> reversedBy(EntryRef entry);

    /** Remittance information carried by the entry, if any was supplied. */
    Optional<String> entryReference(EntryRef entry);

    /**
     * Attaches remittance information to an entry.
     *
     * <p>Called in the same transaction as the append. It is not part of {@code JournalEntry}
     * because no double-entry invariant depends on free text, and putting it on the aggregate would
     * mean the balancing rules had a 35-character customer-supplied field inside them.
     */
    void recordEntryReference(EntryRef entry, String reference);

    /**
     * One page of an account's statement, oldest first.
     *
     * <p>The query is keyset, not offset: {@code cursor} names the last movement of the previous
     * page and the next page begins strictly after it. An offset would skip and duplicate rows while
     * movements are being posted, which on a statement is a wrong answer rather than a slow one.
     *
     * @param cursor an opaque cursor from a previous page, or null for the first page
     * @param limit the maximum number of movements to return
     * @throws IllegalArgumentException if the cursor was not issued by this ledger
     */
    StatementPage statementPage(
            AccountRef account, LocalDate from, LocalDate to, String cursor, int limit);
}
