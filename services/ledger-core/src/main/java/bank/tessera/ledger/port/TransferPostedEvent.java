package bank.tessera.ledger.port;

import bank.tessera.ledger.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The event a posted transfer produces, shaped by {@code contracts/asyncapi/ledger-events.yaml}.
 *
 * <p>Every field here exists because that contract requires it. The contract came first, in WP-02;
 * this is the implementation catching up, and a field that appears here without appearing there is a
 * defect rather than an extension.
 *
 * <p><strong>The remittance reference is carried, unlike in the audit trail.</strong> The two
 * exclusions look inconsistent and are not: an audit row is an internal record retained for years,
 * while this event is how the reference reaches the tiers that need it - the ESB encodes it into a
 * {@code MOVEREC} and the mainframe prints it. Withholding it here would break the flow the whole
 * estate exists to carry.
 *
 * <p>Pure Java, like every port type. It is turned into JSON by the adapter, which is the only place
 * that knows the wire format.
 */
public record TransferPostedEvent(
        String transferRef,
        String debitAccountRef,
        String creditAccountRef,
        Money amount,
        String reference,
        Instant postedAt,
        String reversesTransferRef,
        List<Movement> movements,
        String correlationId) {

    public TransferPostedEvent {
        Objects.requireNonNull(transferRef, "transferRef");
        Objects.requireNonNull(debitAccountRef, "debitAccountRef");
        Objects.requireNonNull(creditAccountRef, "creditAccountRef");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(postedAt, "postedAt");
        movements = List.copyOf(Objects.requireNonNull(movements, "movements"));
        if (movements.size() != 2) {
            // The contract says exactly two, and it says so because a consumer that assumed a pair
            // would silently drop a third leg. This ledger only ever writes pairs; if that ever
            // changes, the contract changes first and this line is where it is noticed.
            throw new IllegalArgumentException(
                    "A transfer event carries exactly two movements, but had " + movements.size() + ".");
        }
    }

    /** One leg. {@code legNo} 1 is the debit and 2 the credit, as the contract states. */
    public record Movement(
            String movementRef,
            String transferRef,
            int legNo,
            String accountRef,
            String direction,
            Money amount,
            LocalDate valueDate,
            Instant postedAt,
            String reference) {}
}
