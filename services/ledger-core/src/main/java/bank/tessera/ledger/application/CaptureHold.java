package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.port.HoldRepository;
import bank.tessera.ledger.port.UnitOfWork;
import java.time.Clock;
import java.util.Objects;

/**
 * Turns a hold into a transfer, and clears the hold, in one transaction.
 *
 * <p>The two must be atomic. Post the transfer and leave the hold {@code PLACED} and the customer is
 * charged twice over: once in booked balance and once again in the reservation still reducing what
 * they can spend. Clear the hold first and fail to post and the reservation is gone with nothing
 * having moved.
 *
 * <p>The posting itself goes through {@link Transfer}, not through a second copy of it here. The
 * account checks, the currency rule, the overdraft decision and the lock ordering are all the same
 * rules, and a capture that enforced its own version of them would be the place they first diverge.
 */
public final class CaptureHold {

    private final HoldRepository holds;
    private final Transfer transfer;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public CaptureHold(HoldRepository holds, Transfer transfer, UnitOfWork unitOfWork, Clock clock) {
        this.holds = Objects.requireNonNull(holds, "holds");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @throws HoldNotFoundException if the hold does not exist
     * @throws NotActionableException if the capture would exceed the amount reserved
     * @throws IllegalStateException if the hold is not {@code PLACED}
     */
    public TransferView execute(Command command) {
        Objects.requireNonNull(command, "command");

        return unitOfWork.inTransaction(() -> {
            Hold hold = holds.findByReference(command.hold())
                    .orElseThrow(() -> new HoldNotFoundException(command.hold()));

            if (!hold.isActive()) {
                // Checked before the transfer is posted, not left to hold.capture() afterwards. The
                // transaction would roll the transfer back either way, but a use case that relies on
                // a rollback to undo work it should never have started is one refactor away from
                // doing it outside a transaction.
                throw new IllegalStateException(
                        "Hold " + hold.reference() + " is " + hold.status() + " and cannot be captured.");
            }

            if (command.amount().compareTo(hold.amount()) > 0) {
                // Capturing more than was reserved is not a larger capture, it is a different
                // transaction that never passed an authorisation. A partial capture is allowed and
                // ordinary - a merchant shipping half an order.
                throw new NotActionableException("Capture of " + command.amount().toPlainString()
                        + " " + command.amount().currency() + " exceeds the "
                        + hold.amount().toPlainString() + " " + hold.amount().currency()
                        + " reserved by hold " + hold.reference() + ".");
            }

            TransferView posted = transfer.execute(new Transfer.Command(
                    hold.account(), command.creditAccount(), command.amount(), null, command.reference()));

            // The domain refuses a transition out of anything but PLACED, so it remains the
            // authority even though the guard above has already answered. Belt and braces on the one
            // path where being wrong means charging a customer twice.
            holds.save(hold.capture(posted.transferReference(), clock.instant()));
            return posted;
        });
    }

    public record Command(HoldRef hold, AccountRef creditAccount, Money amount, String reference) {

        public Command {
            Objects.requireNonNull(hold, "hold");
            Objects.requireNonNull(creditAccount, "creditAccount");
            Objects.requireNonNull(amount, "amount");
        }
    }
}
