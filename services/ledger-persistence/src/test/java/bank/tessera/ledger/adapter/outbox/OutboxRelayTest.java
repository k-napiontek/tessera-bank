package bank.tessera.ledger.adapter.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import bank.tessera.ledger.adapter.jdbc.PostgresSupport;
import bank.tessera.ledger.adapter.jdbc.Transactions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The relay's one guarantee: an event is never lost, and may therefore arrive twice.
 *
 * <p>The publisher here is a fake, and deliberately. What is under test is the order of two steps
 * and what the table looks like when the process dies between them - a real broker would make that
 * window harder to hit and prove nothing more. The Kafka adapter is proved against a real broker in
 * {@code ledger-api}, where the question is whether it speaks the protocol.
 */
class OutboxRelayTest {

    private NamedParameterJdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private List<PendingMessage> published;

    @BeforeEach
    void migrate() {
        // A schema per test: these tests are about what the table looks like afterwards, so a row
        // left by a neighbour would be indistinguishable from the row under test.
        DataSource dataSource =
                PostgresSupport.migratedSchema("relay_" + java.util.UUID.randomUUID().toString().substring(0, 8));
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        transactions = Transactions.of(dataSource);
        published = new ArrayList<>();
    }

    private long enqueue(String key, String payload) {
        Long id = jdbc.queryForObject(
                "INSERT INTO outbox_record (topic, message_key, payload)"
                        + " VALUES ('tessera.ledger.transfer-posted.v1', :key, CAST(:payload AS jsonb))"
                        + " RETURNING id",
                Map.of("key", key, "payload", payload),
                Long.class);
        return id == null ? 0L : id;
    }

    private OutboxRelay relayWith(EventPublisher publisher) {
        return new OutboxRelay(jdbc, transactions, publisher);
    }

    private EventPublisher recording() {
        return published::add;
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap(
                "SELECT dispatched_at, attempts, last_error FROM outbox_record WHERE id = :id",
                Map.of("id", id));
    }

    @Test
    @DisplayName("a pending event is published and marked dispatched")
    void aPendingEventIsPublished() {
        long id = enqueue("TB202608190000000001", "{\"transferRef\": \"TB202608190000000001\"}");

        assertThat(relayWith(recording()).dispatchBatch(10)).isEqualTo(1);

        assertThat(published).hasSize(1);
        assertThat(published.get(0).key()).isEqualTo("TB202608190000000001");
        assertThat(published.get(0).topic()).isEqualTo("tessera.ledger.transfer-posted.v1");
        assertThat(row(id).get("dispatched_at")).isNotNull();
        assertThat(row(id).get("attempts")).isEqualTo(1);
    }

    @Test
    @DisplayName("a dispatched event is never published a second time")
    void aDispatchedEventIsNotRepublished() {
        enqueue("TB202608190000000002", "{}");
        OutboxRelay relay = relayWith(recording());

        relay.dispatchBatch(10);
        relay.dispatchBatch(10);

        assertThat(published).hasSize(1);
        assertThat(relay.pending()).isZero();
    }

    @Test
    @DisplayName("a publish the broker refuses leaves the row pending, with the attempt recorded")
    void aRefusedPublishStaysPending() {
        long id = enqueue("TB202608190000000003", "{}");
        EventPublisher refusing = message -> {
            throw new IllegalStateException("broker unavailable");
        };

        assertThat(relayWith(refusing).dispatchBatch(10)).isZero();

        assertThat(row(id).get("dispatched_at")).isNull();
        assertThat(row(id).get("attempts")).isEqualTo(1);
        assertThat(String.valueOf(row(id).get("last_error"))).contains("broker unavailable");

        // And the next pass, against a broker that is back, publishes it.
        assertThat(relayWith(recording()).dispatchBatch(10)).isEqualTo(1);
        assertThat(row(id).get("dispatched_at")).isNotNull();
    }

    @Test
    @DisplayName("the relay dying between publish and mark republishes rather than losing the event")
    void republishesAfterFailureBeforeMark() {
        // The window the whole design is about. The broker has the event; the row has not been
        // marked; the process stops existing. Modelled by rolling back the transaction the relay ran
        // in, which is exactly what a crash does to it.
        long id = enqueue("TB202608190000000004", "{}");

        transactions.execute(status -> {
            relayWith(recording()).dispatchBatch(10);
            status.setRollbackOnly();
            return null;
        });

        assertThat(published).as("the broker did receive it").hasSize(1);
        assertThat(row(id).get("dispatched_at")).as("but the row was never marked").isNull();

        assertThat(relayWith(recording()).dispatchBatch(10)).isEqualTo(1);

        // Twice, and that is correct. The AsyncAPI document requires every consumer to de-duplicate
        // on transferRef precisely so that this is recoverable rather than a defect.
        assertThat(published).hasSize(2);
        assertThat(published.get(0).key()).isEqualTo(published.get(1).key());
        assertThat(row(id).get("dispatched_at")).isNotNull();
    }

    @Test
    @DisplayName("a batch stops at its first failure, so events keep their order")
    void aBatchStopsAtItsFirstFailure() {
        long first = enqueue("TB202608190000000005", "{}");
        long second = enqueue("TB202608190000000006", "{}");
        long third = enqueue("TB202608190000000007", "{}");

        EventPublisher refusingTheSecond = message -> {
            if (message.id() == second) {
                throw new IllegalStateException("broker rejected this one");
            }
            published.add(message);
        };

        assertThat(relayWith(refusingTheSecond).dispatchBatch(10)).isEqualTo(1);

        assertThat(row(first).get("dispatched_at")).isNotNull();
        assertThat(row(second).get("dispatched_at")).isNull();
        // The third was claimed and deliberately left alone. Publishing it while the second is stuck
        // would deliver a reversal ahead of the transfer it reverses.
        assertThat(row(third).get("dispatched_at")).isNull();
        assertThat(published).hasSize(1);
    }

    @Test
    @DisplayName("the batch limit is honoured")
    void theBatchLimitIsHonoured() {
        enqueue("TB202608190000000008", "{}");
        enqueue("TB202608190000000009", "{}");
        enqueue("TB202608190000000010", "{}");

        OutboxRelay relay = relayWith(recording());

        assertThat(relay.dispatchBatch(2)).isEqualTo(2);
        assertThat(relay.pending()).isEqualTo(1);
    }
}
