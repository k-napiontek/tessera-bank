package bank.tessera.ledger.api.outbox;

import bank.tessera.ledger.adapter.outbox.OutboxRelay;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Drives the relay on a timer.
 *
 * <p>A timer rather than a callback from the transfer path, deliberately: the point of an outbox is
 * that publishing is nobody's business but the relay's, and a transfer that waited on Kafka would
 * have coupled the customer's request to the broker's availability - which is the arrangement the
 * outbox replaced.
 *
 * <p>Every instance runs this. {@code FOR UPDATE SKIP LOCKED} in the relay is what makes that safe,
 * and what lets throughput grow with the number of instances instead of being fixed by a leader.
 */
public class OutboxRelayScheduler {

    private final OutboxRelay relay;
    private final int batchSize;

    public OutboxRelayScheduler(OutboxRelay relay, int batchSize) {
        this.relay = Objects.requireNonNull(relay, "relay");
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${tessera.outbox.relay-interval-ms:500}")
    public void relay() {
        // fixedDelay, not fixedRate: the next pass starts after this one finishes, so a slow broker
        // makes the relay run less often rather than piling up overlapping passes against it.
        relay.dispatchBatch(batchSize);
    }
}
