package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * A dead letter is validated against the contract that declares it, not against this module's
 * opinion of that contract.
 *
 * <p>The schema is read from {@code contracts/asyncapi/esb-adapter-events.yaml} at test time - the
 * same document a consumer would generate from - so the two cannot drift. This is the same control
 * `ledger-api` applies to its OpenAPI document and `customer-master` to its XSD, using the same
 * JSON Schema implementation as the former.
 *
 * <p>The other assertions here are all about disclosure. A dead-letter topic is retained and widely
 * readable, and the original payload can carry a remittance reference - the one field a paying
 * customer controls. A diagnostic that quotes the message body is how a restricted field ends up
 * somewhere nobody classified it for.
 */
class DeadLetterRecorderTest {

    private static final String TOPIC = "tessera.esb.transfer-posted.dlt.v1";
    private static final String TRANSFER_REF = "TB000000000000000042";
    private static final String REMITTANCE = "SALARY AUGUST ACME LTD";
    private static final String PAYLOAD =
            "{\"transferRef\":\"TB000000000000000042\","
                    + "\"reference\":\"" + REMITTANCE + "\","
                    + "\"correlationId\":\"11111111-2222-3333-4444-555555555555\"}";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-20T09:30:00Z"), ZoneOffset.UTC);

    private static JsonSchema deadLetterSchema;

    @BeforeAll
    static void readTheContract() throws IOException {
        File contracts = new File(System.getProperty("tessera.contracts.dir",
                ".." + File.separator + ".." + File.separator + "contracts"));
        File document = new File(contracts, "asyncapi" + File.separator + "esb-adapter-events.yaml");
        assertTrue(document.isFile(), "no contract at " + document.getAbsolutePath());

        JsonNode asyncapi = new ObjectMapper(new YAMLFactory())
                .readTree(Files.readAllBytes(document.toPath()));
        JsonNode schema = asyncapi.at("/components/schemas/DeadLetterPayload");
        assertFalse(schema.isMissingNode(), "the contract declares no DeadLetterPayload");

        deadLetterSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                .getSchema(schema);
    }

    @Test
    void aDeadLetterSatisfiesTheContractThatDeclaresIt() throws Exception {
        JsonNode written = record(TRANSFER_REF, PAYLOAD, TransferHandlingException.permanent(
                FailureStage.SOAP, "ACCT_NOT_FOUND", "the system of record does not know an account"));

        assertValid(written);
        assertEquals(TRANSFER_REF, written.get("transferRef").asText());
        assertEquals("SOAP", written.get("failedStage").asText());
        assertEquals("ACCT_NOT_FOUND", written.get("faultCode").asText());
        assertEquals("2026-08-20T09:30:00Z", written.get("failedAt").asText());
    }

    /**
     * The worst message is still recorded. A schema requiring transferRef would make an unparseable
     * message with no usable key the only kind that could not be dead-lettered - which is the kind
     * the channel exists for.
     */
    @Test
    void aMessageThatCannotBeIdentifiedIsStillARecordableDeadLetter() throws Exception {
        JsonNode written = record(null, "{{{ not json",
                TransferHandlingException.permanent(FailureStage.DEQUEUE, "not JSON"));

        assertValid(written);
        assertFalse(written.has("transferRef"), "an absent transferRef must be omitted, not null");
    }

    @Test
    void theOriginalPayloadIsCarriedVerbatim() throws Exception {
        JsonNode written = record(TRANSFER_REF, PAYLOAD,
                TransferHandlingException.permanent(FailureStage.TRANSFORM, "schema violation"));

        assertEquals(PAYLOAD, written.get("originalPayload").asText(),
                "the payload was re-serialised - the evidence of why it failed to parse is gone");
    }

    /** The disclosure test. Everything outside originalPayload must be free of the message body. */
    @Test
    void theReasonNeverRepeatsTheRemittanceReference() throws Exception {
        JsonNode written = record(TRANSFER_REF, PAYLOAD, TransferHandlingException.permanent(
                FailureStage.SOAP, "the system of record refused the movement"));

        assertFalse(written.get("reason").asText().contains(REMITTANCE),
                "a remittance reference reached the reason field");

        ((com.fasterxml.jackson.databind.node.ObjectNode) written).remove("originalPayload");
        assertFalse(JSON.writeValueAsString(written).contains(REMITTANCE),
                "a remittance reference reached a field other than originalPayload");
    }

    @Test
    void theCorrelationIdIsLiftedSoTheDeadLetterJoinsUpWithTheRestOfTheEstate() throws Exception {
        JsonNode written = record(TRANSFER_REF, PAYLOAD,
                TransferHandlingException.permanent(FailureStage.SOAP, "refused"));

        assertEquals("11111111-2222-3333-4444-555555555555",
                written.get("correlationId").asText());
    }

    /**
     * Truncated here rather than refused by the schema. The moment a reason overruns is the moment
     * something is already going wrong, and a dead letter rejected for being too descriptive is a
     * message lost twice.
     */
    @Test
    void anOverlongReasonIsTruncatedRatherThanRefused() throws Exception {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            tooLong.append("0123456789");
        }
        JsonNode written = record(TRANSFER_REF, PAYLOAD,
                TransferHandlingException.permanent(FailureStage.TRANSFORM, tooLong.toString()));

        assertValid(written);
        assertEquals(500, written.get("reason").asText().length());
    }

    @Test
    void aDeadLetterIsKeyedByTheTransferSoItsRecordsKeepTheirOrder() {
        KafkaTemplate<String, String> kafka = kafkaTemplate();
        new DeadLetterRecorder(kafka, TOPIC, FIXED).record(TRANSFER_REF, PAYLOAD,
                TransferHandlingException.permanent(FailureStage.SOAP, "refused"), 1);

        verify(kafka).send(eq(TOPIC), eq(TRANSFER_REF), any());
    }

    private static void assertValid(JsonNode written) {
        Set<ValidationMessage> problems = deadLetterSchema.validate(written);
        assertTrue(problems.isEmpty(),
                "the dead letter does not satisfy contracts/asyncapi/esb-adapter-events.yaml: "
                        + problems);
    }

    private static JsonNode record(String transferRef, String payload,
            TransferHandlingException failure) throws IOException {
        KafkaTemplate<String, String> kafka = kafkaTemplate();
        new DeadLetterRecorder(kafka, TOPIC, FIXED).record(transferRef, payload, failure, 1);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(TOPIC), Mockito.nullable(String.class), body.capture());
        return JSON.readTree(body.getValue());
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, String> kafkaTemplate() {
        return (KafkaTemplate<String, String>) Mockito.mock(KafkaTemplate.class);
    }
}
