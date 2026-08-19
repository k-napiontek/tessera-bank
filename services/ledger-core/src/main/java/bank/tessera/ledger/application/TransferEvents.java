package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.Posting;
import bank.tessera.ledger.port.AuditContext;
import bank.tessera.ledger.port.EventOutbox;
import bank.tessera.ledger.port.TransferPostedEvent;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Turns a transfer the ledger has just recorded into the event the estate consumes.
 *
 * <p>Shaped like {@link AuditTrail}, and for the same reason: one constructor argument per use case
 * rather than three, and one place that decides what an event contains. The mapping lives here, in
 * the application layer, so that {@code TransferPostedEvent} stays a leaf type in {@code port} with
 * nothing above it to depend on.
 *
 * <p>The correlation id comes from {@link AuditContext}. The port is named for its first consumer,
 * but the identity it carries is the request's, and an event correlating to something different from
 * the audit row written in the same transaction would defeat the purpose of having either.
 *
 * <p><strong>Called inside the caller's transaction.</strong> The row lands beside the postings or
 * not at all - see {@link EventOutbox} for why that is the whole design.
 */
public final class TransferEvents {

    private final EventOutbox outbox;
    private final AuditContext context;

    public TransferEvents(EventOutbox outbox, AuditContext context) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.context = Objects.requireNonNull(context, "context");
    }

    public void publish(TransferView posted) {
        Objects.requireNonNull(posted, "posted");
        outbox.publish(eventFor(posted, context.correlationId().orElse(null)));
    }

    /** Visible for testing the mapping without a transaction or an outbox. */
    static TransferPostedEvent eventFor(TransferView posted, String correlationId) {
        String transferRef = posted.transferReference().value();
        List<Posting> postings = posted.entry().postings();

        List<TransferPostedEvent.Movement> movements = IntStream.range(0, postings.size())
                .mapToObj(index -> {
                    Posting posting = postings.get(index);
                    int legNo = index + 1;
                    return new TransferPostedEvent.Movement(
                            // The contract's movementRef pattern: the transfer reference and the leg
                            // number, so a movement is identifiable without a second lookup.
                            String.format("%s-%02d", transferRef, legNo),
                            transferRef,
                            legNo,
                            posting.account().value(),
                            posting.direction().name(),
                            posting.amount(),
                            posted.entry().valueDate(),
                            posted.postedAt(),
                            posted.remittanceReference().orElse(null));
                })
                .toList();

        return new TransferPostedEvent(
                transferRef,
                posted.debitAccount().value(),
                posted.creditAccount().value(),
                posted.amount(),
                posted.remittanceReference().orElse(null),
                posted.postedAt(),
                posted.entry().reverses().map(reversed -> reversed.value()).orElse(null),
                movements,
                correlationId);
    }
}
