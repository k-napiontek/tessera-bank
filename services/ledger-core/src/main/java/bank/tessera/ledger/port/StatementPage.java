package bank.tessera.ledger.port;

import bank.tessera.ledger.domain.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of an account statement: the movements, where the balance stood before them, and where
 * to resume.
 *
 * <p>{@code openingBalance} is the booked balance immediately before the first movement on this
 * page, so the caller can add the page's own effects and arrive at its closing balance. That is what
 * makes a page checkable on its own rather than only after every page has been fetched.
 *
 * <p>{@code nextCursor} is opaque and empty on the last page. It encodes the sort key of the last
 * movement here, so the next page is fetched with a "greater than this key" predicate rather than by
 * counting rows to skip - a movement posted between two reads then lands on one side of the
 * boundary or the other, and can be neither missed nor repeated.
 */
public final class StatementPage {

    private final List<Movement> movements;
    private final Money openingBalance;
    private final String nextCursor;

    private StatementPage(List<Movement> movements, Money openingBalance, String nextCursor) {
        this.movements = movements;
        this.openingBalance = openingBalance;
        this.nextCursor = nextCursor;
    }

    /** @param nextCursor null when this is the last page */
    public static StatementPage of(List<Movement> movements, Money openingBalance, String nextCursor) {
        return new StatementPage(
                List.copyOf(Objects.requireNonNull(movements, "movements")),
                Objects.requireNonNull(openingBalance, "openingBalance"),
                nextCursor);
    }

    public List<Movement> movements() {
        return movements;
    }

    public Money openingBalance() {
        return openingBalance;
    }

    public Optional<String> nextCursor() {
        return Optional.ofNullable(nextCursor);
    }

    @Override
    public String toString() {
        return "StatementPage[" + movements.size() + " movements from " + openingBalance + "]";
    }
}
