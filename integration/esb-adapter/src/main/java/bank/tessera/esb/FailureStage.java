package bank.tessera.esb;

/**
 * Where a message stopped. The stages are eras, not steps.
 *
 * <p>An operator reads this first, because it decides who is paged: TRANSFORM is 2019 failing to
 * become 2011 and belongs to whoever owns the stylesheet; SOAP is the system of record refusing
 * something and usually belongs to the bank rather than to this component; ENCODE and WRITE are
 * 2011 failing to become 1995 and belong to the batch team.
 *
 * <p>The values are declared in {@code contracts/asyncapi/esb-adapter-events.yaml} and this
 * enumeration follows that contract rather than the other way round.
 */
public enum FailureStage {

    /** The message could not be read off the topic at all - not valid JSON, or not a transfer. */
    DEQUEUE,

    /** Canonical JSON would not become canonical XML, or the result failed the schema. */
    TRANSFORM,

    /** The system of record refused it, or could not be reached. */
    SOAP,

    /** WP-11b: an amount or a currency the mainframe's packed decimal cannot represent. */
    ENCODE,

    /** WP-11b: the movement file could not be written. */
    WRITE
}
