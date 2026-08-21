package bank.tessera.ledger.loader;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * One event the model drew, on one business date.
 *
 * <p>Held as the stream carries it - references as strings, the amount as {@code int64} minor units -
 * because most actions are reads that produce no row at all, and turning eight million of them into
 * domain objects to discard nine tenths of them would be the load measuring itself.
 */
public record DrawnAction(
        LocalDate date,
        long seq,
        long atMillis,
        String cohort,
        String operation,
        String customerRef,
        String accountRef,
        String counterpartyRef,
        String transferRef,
        String holdRef,
        long amountMinor,
        String currency) {

    public DrawnAction {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(accountRef, "accountRef");
    }

    /** Whether this action carries an amount, which is the same test the population applies. */
    public boolean movesMoney() {
        return amountMinor != 0;
    }

    /**
     * When it happened.
     *
     * <p>Business dates in this estate carry no location - the model is explicit that they are UTC by
     * construction - so the offset here is the same one the schedule was computed at rather than a
     * choice made in this file.
     */
    public Instant at() {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().plusMillis(atMillis);
    }
}
