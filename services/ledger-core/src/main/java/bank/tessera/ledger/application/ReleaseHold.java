package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.HoldRef;
import bank.tessera.ledger.port.AuditAction;
import bank.tessera.ledger.port.HoldRepository;
import bank.tessera.ledger.port.UnitOfWork;
import java.time.Clock;
import java.util.Map;
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
    private final AuditTrail audit;
    private final Clock clock;

    public ReleaseHold(HoldRepository holds, UnitOfWork unitOfWork, AuditTrail audit, Clock clock) {
        this.holds = Objects.requireNonNull(holds, "holds");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
        this.audit = Objects.requireNonNull(audit, "audit");
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
            Hold released = holds.save(hold.release(clock.instant()));
            audit.record(
                    AuditAction.HOLD_RELEASED,
                    released.reference().value(),
                    Map.of("status", hold.status().name()),
                    Map.of(
                            "status", released.status().name(),
                            "accountRef", released.account().value(),
                            // F-21's instant, doing the job it was closed for: this is when the
                            // reservation actually ended, and until WP-09 the aggregate discarded it.
                            "transitionedAt",
                            released.transitionedAt().orElseThrow().toString()));
            return released;
        });
    }
}
