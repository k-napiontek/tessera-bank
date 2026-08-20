package bank.tessera.esb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Where a message goes when this component cannot carry it across.
 *
 * <p>The payload is declared in {@code contracts/asyncapi/esb-adapter-events.yaml}, which this
 * component owns - a dead letter is something that happened to a consumer, not to the bank, so it
 * does not belong on the ledger's channels.
 *
 * <p>Two rules about what is written, and both are about disclosure rather than tidiness:
 *
 * <ul>
 * <li>The original payload is carried <strong>verbatim and unparsed</strong>. The reason a message
 * is here may be that it could not be parsed, and re-serialising it would destroy the evidence
 * needed to find out why.</li>
 * <li>The reason is written <strong>here</strong> and never quotes the payload. A dead-letter topic
 * is retained and widely readable, and the remittance reference is the one field a paying customer
 * controls - so a diagnostic that echoes the message body is how a restricted field ends up
 * somewhere it was never classified for.</li>
 * </ul>
 */
public class DeadLetterRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterRecorder.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KafkaTemplate<String, String> kafka;
    private final String deadLetterTopic;
    private final Clock clock;

    public DeadLetterRecorder(KafkaTemplate<String, String> kafka, String deadLetterTopic,
            Clock clock) {
        this.kafka = kafka;
        this.deadLetterTopic = deadLetterTopic;
        this.clock = clock;
    }

    public void record(String transferRef, String originalPayload, TransferHandlingException failure,
            int attempts) {
        String message = payloadFor(transferRef, originalPayload, failure, attempts);

        // Logged before it is sent, and with no payload in it. If the send itself fails there is
        // still a record that something was set aside, which is the difference between an incident
        // an operator can start on and one that begins with "we think we lost some transfers".
        LOG.error("dead-lettering transfer {} at stage {}: {}",
                transferRef, failure.stage(), failure.getMessage());

        kafka.send(deadLetterTopic, transferRef, message);
    }

    private String payloadFor(String transferRef, String originalPayload,
            TransferHandlingException failure, int attempts) {
        ObjectNode node = JSON.createObjectNode();
        // Optional in the contract, because a message with no usable key and an unparseable body is
        // exactly what a dead-letter channel is for. Absent here means "identify it by the offset in
        // the log line beside this", not "we lost it".
        if (transferRef != null) {
            node.put("transferRef", transferRef);
        }
        node.put("failedStage", failure.stage().name());
        node.put("reason", reasonOf(failure));
        if (failure.faultCode() != null) {
            node.put("faultCode", failure.faultCode());
        }
        node.put("failedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now(clock)));
        node.put("attempts", attempts);

        String correlationId = correlationIdOf(originalPayload);
        if (correlationId != null) {
            node.put("correlationId", correlationId);
        }
        node.put("originalPayload", originalPayload);

        try {
            return JSON.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            // An ObjectNode this class built cannot fail to serialise. Stated rather than swallowed.
            throw new IllegalStateException("could not serialise a dead letter", impossible);
        }
    }

    /** The contract caps the reason at 500 characters, so it is truncated here rather than refused
     *  by the schema at the moment something is already going wrong. */
    private static String reasonOf(TransferHandlingException failure) {
        String reason = failure.getMessage() == null ? failure.stage().name() : failure.getMessage();
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    /**
     * Lifted out so a dead letter joins up with the ledger's logs and the gateway's for the same
     * customer request. Best effort: the message may be unparseable, which is often exactly why it
     * is here, and failing to dead-letter a message because its correlation id could not be read
     * would lose the very thing being recorded.
     */
    private static String correlationIdOf(String originalPayload) {
        try {
            JsonNode parsed = JSON.readTree(originalPayload);
            JsonNode correlationId = parsed.get("correlationId");
            return correlationId == null || !correlationId.isTextual() ? null
                    : correlationId.asText();
        } catch (Exception unparseable) {
            return null;
        }
    }
}
