package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.port.Movement;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of an account statement, as the API contract describes it.
 *
 * <p>{@code openingBalance} plus every movement on this page equals {@code closingBalance}. That
 * identity holds on each page taken alone, which is the whole reason the balances are page-scoped:
 * a reader can check one page without fetching the rest, and a page that does not foot is a defect
 * rather than a rounding artefact.
 */
public final class StatementView {

    private final AccountRef account;
    private final LocalDate from;
    private final LocalDate to;
    private final Money openingBalance;
    private final Money closingBalance;
    private final List<Movement> movements;
    private final String nextCursor;

    private StatementView(
            AccountRef account,
            LocalDate from,
            LocalDate to,
            Money openingBalance,
            Money closingBalance,
            List<Movement> movements,
            String nextCursor) {
        this.account = account;
        this.from = from;
        this.to = to;
        this.openingBalance = openingBalance;
        this.closingBalance = closingBalance;
        this.movements = movements;
        this.nextCursor = nextCursor;
    }

    static StatementView of(
            AccountRef account,
            LocalDate from,
            LocalDate to,
            Money openingBalance,
            Money closingBalance,
            List<Movement> movements,
            String nextCursor) {
        return new StatementView(
                Objects.requireNonNull(account, "account"),
                Objects.requireNonNull(from, "from"),
                Objects.requireNonNull(to, "to"),
                Objects.requireNonNull(openingBalance, "openingBalance"),
                Objects.requireNonNull(closingBalance, "closingBalance"),
                List.copyOf(Objects.requireNonNull(movements, "movements")),
                nextCursor);
    }

    public AccountRef account() {
        return account;
    }

    public LocalDate from() {
        return from;
    }

    public LocalDate to() {
        return to;
    }

    public Money openingBalance() {
        return openingBalance;
    }

    public Money closingBalance() {
        return closingBalance;
    }

    public List<Movement> movements() {
        return movements;
    }

    /** Empty on the last page. */
    public Optional<String> nextCursor() {
        return Optional.ofNullable(nextCursor);
    }

    @Override
    public String toString() {
        return "StatementView[" + account + " " + from + ".." + to + " " + movements.size() + " movements]";
    }
}
