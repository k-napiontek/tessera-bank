package bank.tessera.esb;

/**
 * A message this component could not carry across, and whether trying again would help.
 *
 * <p>The distinction between permanent and transient is the only thing that decides what happens
 * next, and getting it the wrong way round is expensive in both directions. A permanent failure
 * retried forever blocks every transfer behind it on the same partition. A transient failure
 * dead-lettered immediately discards a perfectly good payment because a network was briefly down.
 *
 * <p>The rule: a failure is <strong>permanent</strong> when re-presenting the identical bytes would
 * fail identically. A malformed payload, a schema violation and a business fault from the system of
 * record are all permanent - the message is wrong, and it will still be wrong in five minutes. A
 * connection refused is not.
 */
public class TransferHandlingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureStage stage;
    private final boolean permanent;
    private final String faultCode;

    private TransferHandlingException(FailureStage stage, boolean permanent, String faultCode,
            String message, Throwable cause) {
        super(message, cause);
        this.stage = stage;
        this.permanent = permanent;
        this.faultCode = faultCode;
    }

    /** The message is wrong. Re-presenting it would fail the same way, so it is set aside. */
    public static TransferHandlingException permanent(FailureStage stage, String message) {
        return new TransferHandlingException(stage, true, null, message, null);
    }

    /** As above, carrying the code the far end named. */
    public static TransferHandlingException permanent(FailureStage stage, String faultCode,
            String message) {
        return new TransferHandlingException(stage, true, faultCode, message, null);
    }

    /**
     * Something was unavailable. The message is not acknowledged, so the broker redelivers it - and
     * the partition behind it waits, which is deliberate. Events for one transfer must keep their
     * order, and skipping past a failure to keep the queue moving can deliver a reversal before the
     * transfer it reverses. The same choice the ledger's outbox relay makes, for the same reason.
     */
    public static TransferHandlingException transient_(FailureStage stage, String message,
            Throwable cause) {
        return new TransferHandlingException(stage, false, null, message, cause);
    }

    public FailureStage stage() {
        return stage;
    }

    public boolean isPermanent() {
        return permanent;
    }

    /** The code the far end named, or null when it named none. */
    public String faultCode() {
        return faultCode;
    }
}
