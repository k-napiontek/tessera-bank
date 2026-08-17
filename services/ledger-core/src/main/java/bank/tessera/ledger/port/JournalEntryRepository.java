package bank.tessera.ledger.port;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.Balance;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.JournalEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Storage of journal entries.
 *
 * <p><strong>Append-only.</strong> There is no update and no delete, at this port or anywhere below
 * it. A correction is a reversing entry, and an adapter that offered a way around that would break
 * the invariant the domain exists to hold.
 */
public interface JournalEntryRepository {

    JournalEntry append(JournalEntry entry);

    Optional<JournalEntry> findByReference(EntryRef reference);

    /** Entries touching an account over an inclusive value-date range, oldest first. */
    List<JournalEntry> findByAccount(AccountRef account, LocalDate from, LocalDate to);

    /** The balance derived from every posting on the account, plus its active holds. */
    Balance balanceOf(AccountRef account);
}
