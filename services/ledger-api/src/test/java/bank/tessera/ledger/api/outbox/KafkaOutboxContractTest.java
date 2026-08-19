package bank.tessera.ledger.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.tessera.ledger.api.contract.AsyncApiContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A transfer over HTTP, relayed to a real broker, checked against the AsyncAPI document.
 *
 * <p>Everything else about the outbox is proved without Kafka: {@code OutboxRelayTest} proves the
 * relay never loses an event, and {@code OutboxTransactionTest} proves the row shares the postings'
 * fate. What needs a broker is the part a fake cannot answer - whether what leaves this service is
 * something a consumer written in another language can actually read.
 *
 * <p>So every assertion here is about the wire: the topic the contract names, the key pattern its
 * Kafka binding declares, and the payload validated against the message's schema. A field this
 * service invents fails the schema; a field the contract requires and the service omits fails it
 * too.
 */
// A context of its own: the relay is on here and off everywhere else, and Spring discriminates on
// properties when caching contexts, so this does not disturb the shared one.
@SpringBootTest(properties = {"tessera.outbox.relay-enabled=true", "tessera.outbox.relay-interval-ms=200"})
@AutoConfigureMockMvc
class KafkaOutboxContractTest {

    private static final String CHANNEL = "transferPosted";
    private static final String MESSAGE = "transferPosted";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("tessera_ledger")
            .withUsername("ledger")
            .withPassword("ledger");

    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    static {
        // Started here rather than by @Testcontainers, for the reason LedgerApiTest records: that
        // extension stops a container when the declaring class finishes while Spring keeps the
        // context and its pool, and the next class fails for a reason unrelated to what it tested.
        POSTGRES.start();
        KAFKA.start();
    }

    private static int nextAccount = 9000;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private static synchronized String freshAccountReference() {
        return String.format("TB%014d", nextAccount++);
    }

    private String open(String reference, String type) throws Exception {
        String body = """
                {
                  "accountRef": "%s",
                  "customerRef": "CU0000000001",
                  "accountType": "%s",
                  "currency": "PLN"
                }
                """
                .formatted(reference, type);
        mvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return reference;
    }

    @Test
    @DisplayName("a posted transfer reaches the contracted topic, keyed and shaped as the contract says")
    void aPostedTransferReachesTheBroker() throws Exception {
        AsyncApiContract contract = new AsyncApiContract();
        String topic = contract.topicOf(CHANNEL);

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(topic));

            String vault = open(freshAccountReference(), "ASSET");
            String alice = open(freshAccountReference(), "LIABILITY");
            String correlationId = UUID.randomUUID().toString();

            mvc.perform(post("/v1/transfers")
                            .header("Idempotency-Key", "kafka-" + UUID.randomUUID())
                            .header("X-Correlation-Id", correlationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "debitAccountRef": "%s",
                                      "creditAccountRef": "%s",
                                      "amount": { "amountMinor": 12345, "currency": "PLN" },
                                      "reference": "INV-9001"
                                    }
                                    """
                                    .formatted(vault, alice)))
                    .andExpect(status().isCreated());

            ConsumerRecord<String, String> published = awaitOne(consumer);

            assertThat(published.key())
                    .as("keyed by transferRef, so every event for one transfer keeps its order")
                    .matches(contract.keyPatternOf(CHANNEL, MESSAGE));

            assertThat(contract.validatePayload(CHANNEL, MESSAGE, published.value()))
                    .as("the published payload must satisfy the schema the contract declares")
                    .isEmpty();

            var payload = new ObjectMapper().readTree(published.value());
            assertThat(payload.get("transferRef").asText()).isEqualTo(published.key());
            assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
            assertThat(payload.get("amount").get("amountMinor").asLong()).isEqualTo(12345L);

            // And the relay marked what it published, so a restart does not replay the topic.
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM outbox_record"
                                    + " WHERE message_key = :key AND dispatched_at IS NOT NULL",
                            Map.of("key", published.key()),
                            Long.class))
                    .isEqualTo(1L);
        }
    }

    private static ConsumerRecord<String, String> awaitOne(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                return record;
            }
        }
        throw new AssertionError(
                "No transfer event reached the broker within 30 seconds. The relay is scheduled, so "
                        + "either it never ran or the publish was refused.");
    }

    private static KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "contract-test-" + UUID.randomUUID(),
                // earliest, with a group id nobody else uses. The topic does not exist until the
                // relay publishes to it, and a consumer subscribed to a topic that is not there yet
                // is assigned no partitions - so seeking to the end assigns nothing and the first
                // event, which creates the topic, is the one that gets missed. The container is
                // fresh per run, so everything on this topic is this test's.
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "false",
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName()));
    }
}
