package bank.tessera.ledger.adapter.outbox;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves committed events from the outbox to the broker.
 *
 * <p><strong>Publish, then mark. Never the other way round.</strong> Marking first and publishing
 * afterwards loses the event whenever the publish fails, and no retry can recover it because the row
 * already claims to be done. Publishing first means the relay can die in the window between the
 * broker's acknowledgement and the mark - in which case the row is still pending and the next pass
 * publishes it again. That is the at-least-once delivery the AsyncAPI document declares, and it is
 * the deliberate cost of never losing one.
 *
 * <p><strong>{@code FOR UPDATE SKIP LOCKED}.</strong> More than one instance of this service will
 * run, and all of them will relay. Skip-locked lets each take rows the others are not working on
 * without any of them blocking, which is the difference between horizontal scaling and a queue.
 *
 * <p><strong>A batch stops at its first failure.</strong> The rows are ordered by id, and events for
 * one transfer must reach the broker in the order they were written - publishing row 7 after row 6
 * failed would deliver a reversal before the transfer it reverses. The remaining rows stay claimed
 * until the transaction ends and are picked up unchanged on the next pass.
 *
 * <p>Nothing here ever gives up. There is no retry limit and no dead-letter path, because an event
 * quietly abandoned is a ledger and its consumers diverging with nobody told. {@code attempts} and
 * {@code last_error} are what an operator reads; the outbox-lag metric is what pages them.
 */
public final class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EventPublisher publisher;

    public OutboxRelay(
            NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, EventPublisher publisher) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /**
     * Publishes up to {@code limit} pending events.
     *
     * @return how many were published and marked
     */
    public int dispatchBatch(int limit) {
        Integer published = transactions.execute(status -> {
            List<PendingMessage> pending = jdbc.query(
                    """
                    SELECT id, topic, message_key, payload::text AS payload
                      FROM outbox_record
                     WHERE dispatched_at IS NULL
                     ORDER BY id
                       FOR UPDATE SKIP LOCKED
                     LIMIT :limit
                    """,
                    Map.of("limit", limit),
                    (row, number) -> new PendingMessage(
                            row.getLong("id"),
                            row.getString("topic"),
                            row.getString("message_key"),
                            row.getString("payload")));

            int count = 0;
            for (PendingMessage message : pending) {
                try {
                    publisher.publish(message);
                } catch (RuntimeException refused) {
                    recordFailure(message, refused);
                    // Stop here rather than skipping ahead. See the class comment: order per key is
                    // a promise the AsyncAPI document makes.
                    break;
                }
                // Outside the try on purpose. A failure to mark is not a failure to publish, and
                // handling it here would let a published event be quietly recorded as failed - the
                // transaction rolls back instead and the next pass republishes it.
                markDispatched(message);
                count++;
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    /** How many events are waiting. The number an operator and the lag metric both want. */
    public long pending() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_record WHERE dispatched_at IS NULL", Map.of(), Long.class);
        return count == null ? 0L : count;
    }

    private void markDispatched(PendingMessage message) {
        jdbc.update(
                "UPDATE outbox_record SET dispatched_at = now(), attempts = attempts + 1"
                        + " WHERE id = :id",
                Map.of("id", message.id()));
    }

    private void recordFailure(PendingMessage message, RuntimeException refused) {
        // The message key, never the payload. A failed publish is exactly when somebody reaches for
        // the log, and the payload carries a remittance reference the payer wrote.
        LOG.warn(
                "Outbox row {} for key {} was not accepted by the broker: {}",
                message.id(),
                message.key(),
                refused.toString());
        jdbc.update(
                "UPDATE outbox_record SET attempts = attempts + 1, last_error = :error WHERE id = :id",
                Map.of(
                        "id", message.id(),
                        "error", truncated(refused.toString())));
    }

    private static String truncated(String error) {
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
