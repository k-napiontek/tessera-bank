package bank.tessera.ledger.api.outbox;

import bank.tessera.ledger.adapter.outbox.EventPublisher;
import bank.tessera.ledger.adapter.outbox.PendingMessage;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The broker end of the relay.
 *
 * <p><strong>Keyed by {@code transferRef}.</strong> Kafka guarantees order within a partition and
 * nothing across partitions, and the AsyncAPI document promises that every event for one transfer
 * keeps its order. The key is what turns that promise into a property of the deployment rather than
 * an accident of how many partitions the topic happens to have.
 *
 * <p><strong>The send is awaited.</strong> {@code KafkaTemplate.send} returns a future, and returning
 * before it completes would let the relay mark a row dispatched that the broker never accepted - the
 * exact loss the outbox exists to prevent. The wait is bounded, because a relay blocked forever on a
 * broker that is not answering is an outage that looks like a healthy process.
 *
 * <p>The payload goes out as the string the column holds. Re-serialising it here could publish
 * something other than what committed beside the postings.
 */
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final Duration timeout;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, Duration timeout) {
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public void publish(PendingMessage message) {
        try {
            kafka.send(message.topic(), message.key(), message.payload())
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted publishing outbox row " + message.id() + ".", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            // Reported as a failure so the relay leaves the row pending. A timeout is genuinely
            // ambiguous - the broker may have taken it - and leaving the row pending resolves the
            // ambiguity the safe way: at-least-once, never at-most-once.
            throw new IllegalStateException(
                    "The broker did not accept outbox row " + message.id() + ".", failure);
        }
    }
}
