package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.port.EventOutbox;
import bank.tessera.ledger.port.TransferPostedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;

/**
 * The outbox, against PostgreSQL.
 *
 * <p>An insert and nothing else. It opens no transaction, starts no thread and talks to no broker -
 * every one of which would reintroduce the dual write this table exists to avoid. The row lands in
 * the caller's transaction, beside the postings, and the relay takes it from there.
 *
 * <p>The topic is resolved here rather than by the relay, so routing lives in one place. The version
 * suffix is part of the name: a breaking change to the payload creates {@code .v2} beside this topic
 * rather than mutating it, which is what lets consumers migrate on their own schedule.
 */
public final class JdbcEventOutbox implements EventOutbox {

    /** As declared in {@code contracts/asyncapi/ledger-events.yaml}. */
    public static final String TRANSFER_POSTED_TOPIC = "tessera.ledger.transfer-posted.v1";

    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcEventOutbox(
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public void publish(TransferPostedEvent event) {
        Objects.requireNonNull(event, "event");
        jdbc.update(
                """
                INSERT INTO outbox_record (topic, message_key, payload)
                VALUES (:topic, :key, CAST(:payload AS jsonb))
                """,
                Map.of(
                        "topic", TRANSFER_POSTED_TOPIC,
                        "key", event.transferRef(),
                        "payload", serialise(event)));
    }

    private String serialise(TransferPostedEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException failure) {
            // Serialising the payload inside the transaction is deliberate. A relay that discovered
            // an unserialisable event would have a committed transfer it could never announce; here
            // the transfer fails instead, loudly, while the caller is still there to be told.
            throw new IllegalStateException(
                    "Transfer event " + event.transferRef() + " could not be serialised.", failure);
        }
    }
}
