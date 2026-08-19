package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.port.HoldRepository;
import bank.tessera.ledger.port.UnitOfWork;
import java.time.Clock;
import java.util.Objects;

/**
 * Returns a reserved amount to available balance. No money moves.
 *
 * <p>Releasing a hold that is no longer {@code PLACED} is a conflict rather than a no-op. A hold
 * already captured has become a transfer, and telling the caller the release succeeded would let it
 * conclude the reservation is gone when the money is too.
 */
public final class ReleaseHold {

    private final HoldRepository holds;
    private final UnitOfWork unitOfWork;
    private final Clock clock;

    public ReleaseHold(HoldRepository holds, UnitOfWork unitOfWork, Clock clock) {
        this.holds = Objects.requireNonNull(holds, "holds");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @throws HoldNotFoundException if the hold does not exist
     * @throws IllegalStateException if the hold is not {@code PLACED}, which the contract answers
     *     with {@code 409}
     */
    public Hold execute(HoldRef reference) {
        Objects.requireNonNull(reference, "reference");
        return unitOfWork.inTransaction(() -> {
            Hold hold = holds.findByReference(reference)
                    .orElseThrow(() -> new HoldNotFoundException(reference));
            // The domain refuses a transition out of anything but PLACED, so the lifecycle rule is
            // enforced once, where it belongs, rather than restated here.
            return holds.save(hold.release(clock.instant()));
        });
    }
}
