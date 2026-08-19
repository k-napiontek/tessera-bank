package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.port.AccountRepository;
import bank.tessera.ledger.port.HoldRepository;
import bank.tessera.ledger.port.UnitOfWork;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lists the holds on an account.
 *
 * <p>Active holds only unless the caller asks for the rest. A captured or released hold reduces
 * nothing and would otherwise be indistinguishable, in a list, from money genuinely reserved.
 */
public final class ListHolds {

    private final AccountRepository accounts;
    private final HoldRepository holds;
    private final UnitOfWork unitOfWork;

    public ListHolds(AccountRepository accounts, HoldRepository holds, UnitOfWork unitOfWork) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    }

    /** Empty when the account does not exist, which the caller must not confuse with no holds. */
    public Optional<List<Hold>> on(AccountRef account, boolean includeInactive) {
        Objects.requireNonNull(account, "account");
        return unitOfWork.inTransaction(() -> accounts.findByReference(account)
                .map(found -> includeInactive
                        ? holds.findAllFor(found.reference())
                        : holds.findActiveFor(found.reference())));
    }
}
