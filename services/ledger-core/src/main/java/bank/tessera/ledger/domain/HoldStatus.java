package bank.tessera.ledger.domain;

/**
 * The lifecycle of a hold. Every transition out of {@link #PLACED} is terminal - a hold is never
 * reopened, because reopening one would make the available balance depend on history rather than on
 * the current set of holds.
 */
public enum HoldStatus {

    /** Reserving part of the available balance. The only state in which a hold has any effect. */
    PLACED,

    /** Consumed by a journal entry. */
    CAPTURED,

    /** Given back without being used. */
    RELEASED,

    /** Timed out. */
    EXPIRED;

    public boolean isActive() {
        return this == PLACED;
    }
}
