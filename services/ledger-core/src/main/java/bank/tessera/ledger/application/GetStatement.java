package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.port.AccountRepository;
import bank.tessera.ledger.port.LedgerReadModel;
import bank.tessera.ledger.port.Movement;
import bank.tessera.ledger.port.StatementPage;
import bank.tessera.ledger.port.UnitOfWork;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of an account's statement over an inclusive value-date range.
 *
 * <p>The closing balance is computed here, from the opening balance the read model supplies plus the
 * page's own movements signed by {@link bank.tessera.ledger.domain.AccountType#signedEffect}. It is
 * deliberately not a second figure read from the database: derived this way, a closing balance that
 * disagrees with the movements printed beside it is impossible rather than merely unlikely, and the
 * page cannot show arithmetic that does not add up.
 */
public final class GetStatement {

    /** The largest page the contract permits, restated here so the domain is not left to trust HTTP. */
    public static final int MAXIMUM_LIMIT = 500;

    public static final int DEFAULT_LIMIT = 100;

    private final AccountRepository accounts;
    private final LedgerReadModel readModel;
    private final UnitOfWork unitOfWork;

    public GetStatement(
            AccountRepository accounts, LedgerReadModel readModel, UnitOfWork unitOfWork) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.readModel = Objects.requireNonNull(readModel, "readModel");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    }

    /**
     * @param cursor the previous page's cursor, or null for the first page
     * @throws IllegalArgumentException if the range is inverted, the limit is out of bounds, or the
     *     cursor was not issued by this ledger
     */
    public Optional<StatementView> of(
            AccountRef account, LocalDate from, LocalDate to, String cursor, int limit) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException(
                    "Statement range ends before it begins: " + from + " to " + to);
        }
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException(
                    "Statement page limit must be between 1 and " + MAXIMUM_LIMIT + ", but was: " + limit);
        }

        return unitOfWork.inTransaction(() -> {
            Optional<Account> found = accounts.findByReference(account);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            Account owner = found.get();
            StatementPage page = readModel.statementPage(account, from, to, cursor, limit);

            Money closing = page.openingBalance();
            for (Movement movement : page.movements()) {
                closing = closing.plus(owner.type().signedEffect(movement.direction(), movement.amount()));
            }

            return Optional.of(StatementView.of(
                    account,
                    from,
                    to,
                    page.openingBalance(),
                    closing,
                    page.movements(),
                    page.nextCursor().orElse(null)));
        });
    }
}
