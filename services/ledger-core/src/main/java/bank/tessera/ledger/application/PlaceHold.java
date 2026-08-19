package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.CurrencyMismatchException;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.port.AccountRepository;
import bank.tessera.ledger.port.HoldRepository;
import bank.tessera.ledger.port.ReferenceGenerator;
import bank.tessera.ledger.port.UnitOfWork;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Reserves part of an account's available balance without moving any money.
 *
 * <p>Booked balance is untouched. That is the whole distinction a hold exists to express: a card
 * authorisation has reduced what the customer can spend, and nothing has left the account yet.
 */
public final class PlaceHold {

    private final AccountRepository accounts;
    private final HoldRepository holds;
    private final ReferenceGenerator references;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public PlaceHold(
            AccountRepository accounts,
            HoldRepository holds,
            ReferenceGenerator references,
            UnitOfWork unitOfWork,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.references = Objects.requireNonNull(references, "references");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Hold execute(Command command) {
        Objects.requireNonNull(command, "command");
        if (!command.amount().isPositive()) {
            throw NotActionableException.amountNotPositive(command.amount());
        }
        Instant placedAt = clock.instant();

        // Before the lock, for the reason Transfer gives: the locking port can only refuse, and its
        // refusal would reach a caller as a conflict rather than as "no such account".
        if (accounts.findByReference(command.account()).isEmpty()) {
            throw new AccountNotFoundException(command.account());
        }

        return unitOfWork.inTransactionLocking(List.of(command.account()), () -> {
            Account account = accounts.findByReference(command.account())
                    .orElseThrow(() -> new AccountNotFoundException(command.account()));
            if (!account.canBePosted()) {
                throw NotActionableException.accountNotOpen(account.reference(), account.status());
            }
            if (!account.currency().equals(command.amount().currency())) {
                throw new CurrencyMismatchException(account.currency(), command.amount().currency());
            }

            // Deliberately no available-balance check. A hold can legitimately exceed what is
            // available - an authorisation arriving after the balance has moved - and Balance
            // reports the resulting negative available honestly rather than flooring it at zero.
            // Refusing here would make the ledger disagree with the card network about a hold the
            // network has already told the customer about.
            HoldRef reference = references.nextHoldReference();
            return holds.save(Hold.place(
                    reference, account.reference(), command.amount(), placedAt, command.expiresAt()));
        });
    }

    /** @param expiresAt when the hold lapses on its own, or null if it does not */
    public record Command(AccountRef account, Money amount, Instant expiresAt, String reference) {

        public Command {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(amount, "amount");
        }
    }
}
