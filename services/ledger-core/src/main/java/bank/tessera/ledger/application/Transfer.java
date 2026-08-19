package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.Balance;
import bank.tessera.ledger.domain.CurrencyMismatchException;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
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
 * Moves money between two accounts in the ledger.
 *
 * <p>This is the use case follow-up F-22 was raised against. WP-06 supplied the decision -
 * {@link Balance#afterEffect} is the one place the overdraft rule lives - and WP-07 supplied
 * deterministic locking and an append that writes both legs in one transaction. Nothing composed
 * them, so {@code append} would happily write an entry taking a forbidden-overdraft account below
 * zero. It does not any more, because of this class.
 *
 * <p>The order matters and is not incidental:
 *
 * <ol>
 *   <li>lock both accounts, in the adapter's deterministic order, before reading anything;
 *   <li>read the accounts and the debit side's balance <em>inside</em> that lock;
 *   <li>ask the domain whether the resulting balance is permitted;
 *   <li>append.
 * </ol>
 *
 * <p>Reading a balance before taking the lock is the classic way to double-spend: two transfers each
 * see the same balance, each decides it is sufficient, and both post.
 */
public final class Transfer {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final LedgerReadModel readModel;
    private final ReferenceGenerator references;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public Transfer(
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
     * @throws AccountNotFoundException if either account is unknown
     * @throws NotActionableException if an account is not {@code OPEN}, the amount is not strictly
     *     positive, or both legs name the same account
     * @throws CurrencyMismatchException if the amount is not in both accounts' currency
     * @throws bank.tessera.ledger.domain.OverdraftNotPermittedException if the debit would breach the
     *     debited account's overdraft policy
     */
    public TransferView execute(Command command) {
        Objects.requireNonNull(command, "command");

        if (!command.amount().isPositive()) {
            throw NotActionableException.amountNotPositive(command.amount());
        }
        if (command.debitAccount().equals(command.creditAccount())) {
            // Permitted by the schema and forbidden by the contract's prose. It would balance
            // perfectly and move nothing, which is the kind of entry that survives a reconciliation
            // and confuses everybody who later reads the statement.
            throw NotActionableException.sameAccount(command.debitAccount());
        }

        LocalDate valueDate =
                command.valueDate() == null ? LocalDate.now(clock) : command.valueDate();

        // Existence is checked before the lock is taken, not after. UnitOfWork.inTransactionLocking
        // refuses to lock an account that does not exist, and it refuses with the only vocabulary a
        // locking port has - which would reach a caller as "conflict" when the truth is "no such
        // account". Accounts are never deleted, so nothing can make this check stale; if that ever
        // changes the lock still refuses and the transaction still aborts.
        require(command.debitAccount());
        require(command.creditAccount());

        return unitOfWork.inTransactionLocking(
                List.of(command.debitAccount(), command.creditAccount()), () -> {
                    Account debit = require(command.debitAccount());
                    Account credit = require(command.creditAccount());

                    requirePostable(debit);
                    requirePostable(credit);
                    requireCurrency(debit, command.amount());
                    requireCurrency(credit, command.amount());

                    // The overdraft decision, made by the domain on the current balance and not by
                    // this class on a remembered one. afterEffect returns a Balance nobody needs -
                    // the value here is that it throws when the policy forbids the result.
                    Balance current = entries.balanceOf(debit.reference());
                    current.afterEffect(
                            debit.type().signedEffect(Direction.DEBIT, command.amount()), debit.overdraft());

                    EntryRef reference = references.nextEntryReference();
                    JournalEntry entry = entries.append(JournalEntry.of(
                            reference,
                            valueDate,
                            List.of(
                                    Posting.of(debit.reference(), Direction.DEBIT, command.amount()),
                                    Posting.of(credit.reference(), Direction.CREDIT, command.amount()))));

                    if (command.reference() != null) {
                        readModel.recordEntryReference(reference, command.reference());
                    }

                    return TransferView.of(
                            entry,
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

    private static void requireCurrency(Account account, Money amount) {
        if (!account.currency().equals(amount.currency())) {
            // There is no conversion anywhere in this estate, so this is a rejection and not a
            // rounding decision. Letting it through would reach Money.plus eventually and fail with
            // a message about arithmetic rather than about the account.
            throw new CurrencyMismatchException(account.currency(), amount.currency());
        }
    }

    /**
     * @param valueDate the date the movement affects the balance, or null for the current business date
     * @param reference remittance information, or null. No personal data.
     */
    public record Command(
            AccountRef debitAccount,
            AccountRef creditAccount,
            Money amount,
            LocalDate valueDate,
            String reference) {

        public Command {
            Objects.requireNonNull(debitAccount, "debitAccount");
            Objects.requireNonNull(creditAccount, "creditAccount");
            Objects.requireNonNull(amount, "amount");
        }
    }
}
