package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.port.AccountDates;
import bank.tessera.ledger.port.AccountRepository;
import bank.tessera.ledger.port.JournalEntryRepository;
import bank.tessera.ledger.port.LedgerReadModel;
import bank.tessera.ledger.port.UnitOfWork;
import java.util.Objects;
import java.util.Optional;

/**
 * Fetches an account with both its balances and its two dates.
 *
 * <p>All three reads happen in one transaction. Outside one they could each observe a different
 * moment, and an account reported with a balance it never held is worse than a slow response.
 */
public final class GetAccount {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final LedgerReadModel readModel;
    private final UnitOfWork unitOfWork;

    public GetAccount(
            AccountRepository accounts,
            JournalEntryRepository entries,
            LedgerReadModel readModel,
            UnitOfWork unitOfWork) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.readModel = Objects.requireNonNull(readModel, "readModel");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    }

    public Optional<AccountView> byReference(AccountRef reference) {
        Objects.requireNonNull(reference, "reference");
        return unitOfWork.inTransaction(() -> {
            Optional<Account> found = accounts.findByReference(reference);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            AccountDates dates = readModel
                    .accountDates(reference)
                    .orElseThrow(() -> new IllegalStateException(
                            "Account " + reference + " exists but has no opening date."));
            return Optional.of(AccountView.of(found.get(), entries.balanceOf(reference), dates));
        });
    }
}
