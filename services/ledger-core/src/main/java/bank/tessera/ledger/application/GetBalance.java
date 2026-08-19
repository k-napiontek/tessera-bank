package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.Balance;
import bank.tessera.ledger.port.AccountRepository;
import bank.tessera.ledger.port.JournalEntryRepository;
import bank.tessera.ledger.port.UnitOfWork;
import java.util.Objects;
import java.util.Optional;

/**
 * Fetches an account's booked and available balances.
 *
 * <p>The account is looked up first so that an unknown reference is a {@code 404} rather than a
 * balance of zero. Those are different answers, and reporting the second for the first is how a
 * caller concludes an account exists and is empty.
 */
public final class GetBalance {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final UnitOfWork unitOfWork;

    public GetBalance(
            AccountRepository accounts, JournalEntryRepository entries, UnitOfWork unitOfWork) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    }

    public Optional<Balance> of(AccountRef reference) {
        Objects.requireNonNull(reference, "reference");
        return unitOfWork.inTransaction(() -> accounts.findByReference(reference)
                .map(account -> entries.balanceOf(account.reference())));
    }
}
