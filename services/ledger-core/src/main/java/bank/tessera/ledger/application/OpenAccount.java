package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.Balance;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.port.AccountDates;
import bank.tessera.ledger.port.AccountRepository;
import bank.tessera.ledger.port.LedgerReadModel;
import bank.tessera.ledger.port.UnitOfWork;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Opens an account in the ledger, at a reference the caller supplies.
 *
 * <p>The ledger does not allocate account numbers. {@code customer-master} owns onboarding and the
 * numbering series, and this component holds no customer identity beyond a pseudonymous reference.
 * That is also what makes the operation idempotent without an {@code Idempotency-Key}: a retry names
 * the same reference, and the second attempt is refused rather than opening a second account.
 */
public final class OpenAccount {

    private final AccountRepository accounts;
    private final LedgerReadModel readModel;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public OpenAccount(
            AccountRepository accounts, LedgerReadModel readModel, UnitOfWork unitOfWork, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.readModel = Objects.requireNonNull(readModel, "readModel");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AccountView open(Command command) {
        Objects.requireNonNull(command, "command");
        LocalDate openedDate =
                command.openedDate() == null ? LocalDate.now(clock) : command.openedDate();

        return unitOfWork.inTransaction(() -> {
            if (accounts.findByReference(command.reference()).isPresent()) {
                throw new AccountAlreadyOpenException(command.reference());
            }

            Account account = Account.builder()
                    .reference(command.reference())
                    .customer(command.customer())
                    .type(command.type())
                    .currency(command.currency())
                    .status(AccountStatus.OPEN)
                    .overdraft(command.overdraft())
                    .build();

            Account saved = accounts.save(account);
            readModel.recordAccountOpened(saved.reference(), openedDate);

            // A new account has no postings and no holds, so both balances are zero in its own
            // currency. Reading them back from the database would prove nothing and cost a query.
            Balance balance = Balance.of(saved.reference(), Money.zero(saved.currency()), List.of());
            return AccountView.of(saved, balance, AccountDates.of(openedDate, null));
        });
    }

    /**
     * @param openedDate the business opening date, or null for the current business date
     * @param overdraft {@code OverdraftPolicy.forbidden()} is not the same as {@code upTo(zero)} - a
     *     zero limit is an arranged facility that happens to be exhausted
     */
    public record Command(
            AccountRef reference,
            CustomerRef customer,
            AccountType type,
            CurrencyCode currency,
            LocalDate openedDate,
            OverdraftPolicy overdraft) {

        public Command {
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(customer, "customer");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(overdraft, "overdraft");
        }
    }
}
