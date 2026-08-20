package bank.tessera.esb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * The consumer end of the bridge.
 *
 * <p>Its only decisions are what to do when the work fails, and there are exactly two answers:
 *
 * <ul>
 * <li><strong>Permanent.</strong> The message is wrong and will still be wrong in five minutes.
 * It is recorded on the dead-letter channel and acknowledged, so the partition keeps moving.</li>
 * <li><strong>Transient.</strong> Something was unavailable. The offset is <em>not</em>
 * acknowledged, the broker redelivers, and everything behind it on that partition waits.</li>
 * </ul>
 *
 * <p>The second one blocks the queue, and that is the intended behaviour rather than a limitation.
 * Events for one transfer are keyed to one partition so they keep their order, and skipping past a
 * failure to keep things moving can deliver a reversal before the transfer it reverses. WP-09 made
 * the same choice on the other side of this topic, where the outbox relay stops a batch at its
 * first failure instead of skipping the stuck row.
 *
 * <p><strong>Idempotency is not this class's.</strong> Delivery is at-least-once and duplicates are
 * expected, and the mechanism that makes that safe is the far end: {@code NotifyTransferPosted} is
 * idempotent on {@code transferRef} and answers {@code alreadyApplied}, because the system of record
 * claims the transfer with a unique constraint. Keeping a second record here of what had already
 * been applied would be a second source of truth about the bank's money that could disagree with the
 * first - which is the drift {@code batch/recon} exists to find, manufactured on purpose. WP-11b
 * needs its own answer for the movement file, which has no such guarantee of its own.
 */
public class TransferPostedListener {

    private static final Logger LOG = LoggerFactory.getLogger(TransferPostedListener.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TRANSFER_REF = Pattern.compile("^TB[0-9]{18}$");

    private final TransferHandler handler;
    private final DeadLetterRecorder deadLetters;

    public TransferPostedListener(TransferHandler handler, DeadLetterRecorder deadLetters) {
        this.handler = handler;
        this.deadLetters = deadLetters;
    }

    @KafkaListener(topics = "${tessera.esb.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String transferRef = transferRefOf(record);

        try {
            handler.handle(record.value());
            ack.acknowledge();
        } catch (TransferHandlingException failure) {
            if (!failure.isPermanent()) {
                // No acknowledgement. The broker redelivers and the partition waits, which is what
                // ordering costs and what it buys.
                LOG.warn("transfer {} could not be carried across at stage {} and will be retried: {}",
                        transferRef, failure.stage(), failure.getMessage());
                throw failure;
            }
            deadLetter(record, transferRef, failure);
            ack.acknowledge();
        } catch (RuntimeException unexpected) {
            // An exception nobody classified. Treated as transient, because the alternative is to
            // discard a payment on the strength of a bug in this component.
            LOG.error("transfer {} failed unexpectedly and will be retried", transferRef, unexpected);
            throw unexpected;
        }
    }

    private void deadLetter(ConsumerRecord<String, String> record, String transferRef,
            TransferHandlingException failure) {
        if (transferRef == null) {
            // The one case the dead-letter channel cannot key. It still records the message; the
            // identity is the coordinates, which is what an operator would search on anyway.
            LOG.error("dead-lettering a message with no usable transferRef, at {}-{} offset {}",
                    record.topic(), record.partition(), record.offset());
        }
        deadLetters.record(transferRef, record.value(), failure, deliveryAttemptOf(record));
    }

    /**
     * The key, per the contract, and the payload only as a fallback. Taking the key first matters:
     * a message that cannot be parsed still has one, and that is the message most in need of being
     * identifiable.
     */
    private static String transferRefOf(ConsumerRecord<String, String> record) {
        if (record.key() != null && TRANSFER_REF.matcher(record.key()).matches()) {
            return record.key();
        }
        try {
            JsonNode payload = JSON.readTree(record.value());
            JsonNode ref = payload.get("transferRef");
            if (ref != null && ref.isTextual() && TRANSFER_REF.matcher(ref.asText()).matches()) {
                return ref.asText();
            }
        } catch (Exception unparseable) {
            // Falls through to null, which the dead-letter channel permits precisely for this case.
        }
        return null;
    }

    /**
     * How many deliveries have been tried. Kafka does not count redeliveries for us - an offset that
     * is never committed is simply fetched again - so what is available is the header Spring adds
     * when it retries, and one delivery when it is absent.
     */
    private static int deliveryAttemptOf(ConsumerRecord<String, String> record) {
        org.apache.kafka.common.header.Header header =
                record.headers().lastHeader("kafka_deliveryAttempt");
        if (header == null || header.value() == null || header.value().length != Integer.BYTES) {
            return 1;
        }
        return java.nio.ByteBuffer.wrap(header.value()).getInt();
    }
}
