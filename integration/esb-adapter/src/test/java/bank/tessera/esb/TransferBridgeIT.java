package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A transfer crossing from 2023 to 2011, for real and end to end.
 *
 * <p>Everything in the estate is running: a real Kafka carrying the ledger's event, a real Oracle
 * holding the system of record, and customer-master's own WAR deployed to a real Tomcat 8.5. The
 * only thing simulated is the ledger itself, and only because publishing a contract-shaped message
 * is all it does that matters here.
 *
 * <p><strong>This is the test the package exists for.</strong> Two independently generated clients
 * meet at the SOAP hop - customer-master generated its server interface from
 * {@code customer-master-v1.wsdl}, this module generated a client from the same document - and
 * nothing whatsoever guarantees they agree until a real call is made. A stub would verify what this
 * component says and never that the far end understands it.
 *
 * <p>Needs Docker and, on a first run, network: Tomcat 8.5.100 is fetched from the Apache archive
 * and the Oracle image is about 2GB. It also needs customer-master to have been packaged, which
 * {@code make test-integration} arranges.
 */
@SpringBootTest(classes = EsbAdapterApplication.class)
class TransferBridgeIT {

    private static final String TOPIC = "tessera.ledger.transfer-posted.v1";
    private static final String DEAD_LETTER_TOPIC = "tessera.esb.transfer-posted.dlt.v1";

    private static final String CUSTOMER = "CU0000000042";
    private static final String DEBIT_ACCOUNT = "TB00000000000001";
    private static final String CREDIT_ACCOUNT = "TB00000000000002";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private static CustomerMasterUnderTest customerMaster;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @BeforeAll
    static void bringUpTheEstate() throws Exception {
        KAFKA.start();
        customerMaster = CustomerMasterUnderTest.start(new File("target/customer-master"));
        seedTwoAccounts();
    }

    @AfterAll
    static void takeItDown() {
        if (customerMaster != null) {
            customerMaster.stop();
        }
        KAFKA.stop();
    }

    @DynamicPropertySource
    static void pointTheAdapterAtThem(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("tessera.esb.customer-master-endpoint", () -> customerMaster.endpoint());
        registry.add("tessera.contracts.dir", () -> new File("../../contracts").getAbsolutePath());
    }

    /**
     * The whole flow. A ledger event goes in; the system of record's balances move.
     *
     * <p>Nothing in this test calls the SOAP endpoint itself - it publishes a Kafka message and then
     * looks at Oracle. Every step in between is the component under test doing its job.
     */
    @Test
    void aPostedTransferReachesTheSystemOfRecord() throws Exception {
        String transferRef = "TB000000000000000101";
        long amount = 25_00L;

        long debitBefore = balanceOf(DEBIT_ACCOUNT);
        long creditBefore = balanceOf(CREDIT_ACCOUNT);

        kafka.send(TOPIC, transferRef, event(transferRef, amount, "PLN"));

        awaitApplied(transferRef);

        assertEquals(debitBefore - amount, balanceOf(DEBIT_ACCOUNT),
                "the debit leg did not move by the sign convention for a LIABILITY account");
        assertEquals(creditBefore + amount, balanceOf(CREDIT_ACCOUNT),
                "the credit leg did not move");
    }

    /**
     * The at-least-once guarantee, paid for once. The outbox relay republishes on a crash between
     * publishing and marking, so a duplicate is an expected event rather than an error - and the
     * mechanism that makes it safe is the far end's unique constraint, not a record kept here.
     */
    @Test
    void aRedeliveredEventMovesTheMoneyOnlyOnce() throws Exception {
        String transferRef = "TB000000000000000102";
        long amount = 13_37L;

        long debitBefore = balanceOf(DEBIT_ACCOUNT);

        kafka.send(TOPIC, transferRef, event(transferRef, amount, "PLN"));
        awaitApplied(transferRef);
        long afterFirst = balanceOf(DEBIT_ACCOUNT);

        kafka.send(TOPIC, transferRef, event(transferRef, amount, "PLN"));
        Thread.sleep(4000);

        assertEquals(debitBefore - amount, afterFirst);
        assertEquals(afterFirst, balanceOf(DEBIT_ACCOUNT),
                "the redelivery moved the money a second time");
    }

    /**
     * A message the stylesheet cannot turn into a valid canonical document is set aside rather than
     * retried forever. It is wrong now and will be wrong on redelivery, so the partition keeps
     * moving and an operator gets something to look at.
     */
    @Test
    void aMessageThatCannotBeTransformedLandsOnTheDeadLetterChannel() throws Exception {
        String transferRef = "TB000000000000000103";
        String malformed = event(transferRef, 100L, "PLN")
                .replace(",\"correlationId\":\"11111111-2222-3333-4444-555555555555\"", "");

        kafka.send(TOPIC, transferRef, malformed);

        JsonNode deadLetter = awaitDeadLetter(transferRef);
        assertNotNull(deadLetter, "nothing reached " + DEAD_LETTER_TOPIC);
        assertEquals("TRANSFORM", deadLetter.get("failedStage").asText());
        assertEquals(malformed, deadLetter.get("originalPayload").asText(),
                "the original message was not carried verbatim");
    }

    /**
     * The estate's scale-2 rule, enforced where the decision log says it belongs: at the integration
     * tier, before the movement reaches a mainframe whose packed decimal cannot represent it.
     * WP-11b asserts the same rule again at the encoder - defence in depth, because a 1995 core does
     * not trust its feeds.
     */
    @Test
    void aCurrencyTheMainframeCannotRepresentIsRefusedBeforeItGetsThere() throws Exception {
        String transferRef = "TB000000000000000104";

        kafka.send(TOPIC, transferRef, event(transferRef, 100L, "JPY"));

        JsonNode deadLetter = awaitDeadLetter(transferRef);
        assertNotNull(deadLetter, "a JPY movement was not refused");
        assertEquals("ENCODE", deadLetter.get("failedStage").asText());
        assertTrue(deadLetter.get("reason").asText().contains("JPY"));

        assertEquals(0, appliedCount(transferRef),
                "a transfer the mainframe cannot represent was applied to the system of record");
    }

    private static void awaitApplied(String transferRef) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            if (appliedCount(transferRef) > 0) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("transfer " + transferRef
                + " never reached the system of record");
    }

    private static int appliedCount(String transferRef) throws Exception {
        try (Connection connection = customerMaster.connection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT COUNT(*) FROM applied_transfer WHERE transfer_ref = ?")) {
            query.setString(1, transferRef);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static long balanceOf(String accountRef) throws Exception {
        try (Connection connection = customerMaster.connection();
                PreparedStatement query = connection.prepareStatement(
                        "SELECT booked_balance FROM account WHERE account_ref = ?")) {
            query.setString(1, accountRef);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private JsonNode awaitDeadLetter(String transferRef) throws Exception {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", KAFKA.getBootstrapServers());
        properties.put("group.id", "dead-letter-reader-" + transferRef);
        properties.put("auto.offset.reset", "earliest");
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Collections.singletonList(DEAD_LETTER_TOPIC));
            for (int attempt = 0; attempt < 40; attempt++) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (transferRef.equals(record.key())) {
                        return JSON.readTree(record.value());
                    }
                }
            }
        }
        return null;
    }

    private static void seedTwoAccounts() throws Exception {
        try (Connection connection = customerMaster.connection();
                Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO customer (customer_ref, family_name, given_name,"
                    + " date_of_birth, national_id, onboarded_date) VALUES ('" + CUSTOMER
                    + "', 'TESSERA-0001', 'SYNTHETIC', DATE '1980-01-01', 'SYN-0000000001',"
                    + " DATE '2011-01-01')");
            statement.execute(openAccount(DEBIT_ACCOUNT, 1_000_00L));
            statement.execute(openAccount(CREDIT_ACCOUNT, 500_00L));
        }
    }

    private static String openAccount(String accountRef, long balanceMinor) {
        return "INSERT INTO account (account_ref, customer_ref, account_type, currency, status,"
                + " booked_balance, opened_date) VALUES ('" + accountRef + "', '" + CUSTOMER
                + "', 'LIABILITY', 'PLN', 'OPEN', " + balanceMinor + ", DATE '2011-01-01')";
    }

    /** Shaped by contracts/asyncapi/ledger-events.yaml - what the ledger's outbox really publishes. */
    private static String event(String transferRef, long amountMinor, String currency) {
        String money = "{\"amountMinor\":" + amountMinor + ",\"currency\":\"" + currency + "\"}";
        return "{"
                + "\"transferRef\":\"" + transferRef + "\","
                + "\"debitAccountRef\":\"" + DEBIT_ACCOUNT + "\","
                + "\"creditAccountRef\":\"" + CREDIT_ACCOUNT + "\","
                + "\"amount\":" + money + ","
                + "\"reference\":\"SALARY\","
                + "\"postedAt\":\"2026-08-20T09:30:00Z\","
                + "\"movements\":["
                + "{\"movementRef\":\"" + transferRef + "-01\",\"transferRef\":\"" + transferRef
                + "\",\"legNo\":1,\"accountRef\":\"" + DEBIT_ACCOUNT + "\",\"direction\":\"DEBIT\","
                + "\"amount\":" + money + ",\"valueDate\":\"2026-08-20\","
                + "\"postedAt\":\"2026-08-20T09:30:00Z\"},"
                + "{\"movementRef\":\"" + transferRef + "-02\",\"transferRef\":\"" + transferRef
                + "\",\"legNo\":2,\"accountRef\":\"" + CREDIT_ACCOUNT + "\",\"direction\":\"CREDIT\","
                + "\"amount\":" + money + ",\"valueDate\":\"2026-08-20\","
                + "\"postedAt\":\"2026-08-20T09:30:00Z\"}"
                + "],"
                + "\"correlationId\":\"11111111-2222-3333-4444-555555555555\""
                + "}";
    }
}
