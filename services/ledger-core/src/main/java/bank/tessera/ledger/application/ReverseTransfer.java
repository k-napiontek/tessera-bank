package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.Balance;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Posting;
import bank.tessera.ledger.port.AccountRepository;
import bank.tessera.ledger.port.JournalEntryRepository;
import bank.tessera.ledger.port.LedgerReadModel;
import bank.tessera.ledger.port.ReferenceGenerator;
import bank.tessera.ledger.port.UnitOfWork;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Reverses a posted transfer by posting its opposite.
 *
 * <p>The original is never mutated and never deleted. A correction is a new entry that names what it
 * corrects, which is what makes the books an audit trail rather than a current state: an auditor can
 * see that a mistake was made and that it was put right, and both facts survive.
 *
 * <p>The reversal is built by {@link JournalEntry#reverse}, the only construction path the domain
 * offers for one. Assembling the opposite postings by hand would produce an entry that looks like a
 * reversal and does not descend from the entry it claims to reverse.
 */
public final class ReverseTransfer {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final LedgerReadModel readModel;
    private final ReferenceGenerator references;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public ReverseTransfer(
            AccountRepository accounts,
            JournalEntryRepository entries,
            LedgerReadModel readModel,
            ReferenceGenerator references,
            UnitOfWork unitOfWork,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.readModel = Objects.requireNonNull(readModel, "readModel");
        this.references = Objects.requireNonNull(references, "references");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @throws TransferNotFoundException if the original does not exist
     * @throws AlreadyReversedException if it has already been reversed
     * @throws NotActionableException if either account is no longer {@code OPEN}
     * @throws bank.tessera.ledger.domain.OverdraftNotPermittedException if the reversal's own debit
     *     would breach a policy - the money may already have been spent onward
     */
    public TransferView execute(Command command) {
        Objects.requireNonNull(command, "command");
        LocalDate valueDate = LocalDate.now(clock);

        JournalEntry original = entries.findByReference(command.original())
                .orElseThrow(() -> new TransferNotFoundException(command.original()));

        List<AccountRef> touched = original.postings().stream()
                .map(Posting::account)
                .distinct()
                .toList();

        return unitOfWork.inTransactionLocking(touched, () -> {
            readModel.reversedBy(command.original()).ifPresent(existing -> {
                throw new AlreadyReversedException(command.original(), existing);
            });

            EntryRef reference = references.nextEntryReference();
            JournalEntry reversal = original.reverse(reference, valueDate);

            // Every account the reversal debits is checked against its own policy. A reversal is not
            // exempt: the money it takes back may already have been spent onward, and an account
            // that forbids an overdraft must not be pushed into one to correct somebody else's error.
            for (Posting posting : reversal.postings()) {
                if (posting.isDebit()) {
                    Account account = require(posting.account());
                    requirePostable(account);
                    Balance current = entries.balanceOf(account.reference());
                    current.afterEffect(posting.effectOn(account.type()), account.overdraft());
                } else {
                    requirePostable(require(posting.account()));
                }
            }

            JournalEntry appended = entries.append(reversal);
            if (command.reference() != null) {
                readModel.recordEntryReference(reference, command.reference());
            }

            return TransferView.of(
                    appended,
                    readModel.entryPostedAt(reference)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Entry " + reference + " was appended but has no posting instant.")),
                    null,
                    command.reference());
        });
    }

    private Account require(AccountRef reference) {
        return accounts.findByReference(reference)
                .orElseThrow(() -> new AccountNotFoundException(reference));
    }

    private static void requirePostable(Account account) {
        if (!account.canBePosted()) {
            throw NotActionableException.accountNotOpen(account.reference(), account.status());
        }
    }

    /**
     * @param reason why the transfer is being reversed. Operator-supplied, and kept out of the
     *     ledger's own records - it belongs to the audit chain WP-09 builds, not to the entry.
     * @param reference remittance information for the reversing entry, or null
     */
    public record Command(EntryRef original, String reason, String reference) {

        public Command {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("A reversal must state a reason.");
            }
        }
    }
}
