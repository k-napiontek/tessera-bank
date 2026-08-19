package bank.tessera.ledger.api.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code contracts/asyncapi/ledger-events.yaml}, loaded and made assertable.
 *
 * <p>The same arrangement as {@link OpenApiContract} and for the same reasons: the document is the
 * source of truth, it is handed to the validator whole so that internal {@code $ref}s resolve as
 * they do in the file, and nothing here restates a field name.
 *
 * <p>AsyncAPI 3.0 payload schemas default to JSON Schema draft 2020-12, so the same validator serves
 * both contracts. What differs is where the schema lives: an AsyncAPI message names its payload,
 * and the topic name comes from the channel's Kafka binding rather than from anything in the code.
 */
public final class AsyncApiContract {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JsonNode document;
    private final JsonSchemaFactory schemas;

    public AsyncApiContract() {
        Path contracts = Path.of(System.getProperty(
                "tessera.contracts.dir", Path.of("..", "..", "contracts").toString()));
        Path file = contracts.resolve("asyncapi").resolve("ledger-events.yaml");
        try {
            this.document = YAML.readTree(Files.readAllBytes(file));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "Cannot read the AsyncAPI contract at " + file.toAbsolutePath(), unreadable);
        }
        this.schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    /** The topic a channel is bound to, taken from the document rather than from the code. */
    public String topicOf(String channel) {
        JsonNode address = document.path("channels").path(channel).path("address");
        if (address.isMissingNode()) {
            throw new IllegalArgumentException("The contract declares no channel " + channel + ".");
        }
        return address.asText();
    }

    /** The key pattern the channel's Kafka binding declares, so a test can hold the key to it. */
    public String keyPatternOf(String channel, String message) {
        JsonNode pattern = messageNode(channel, message)
                .path("bindings")
                .path("kafka")
                .path("key")
                .path("pattern");
        if (pattern.isMissingNode()) {
            throw new IllegalArgumentException(
                    "The contract declares no Kafka key pattern for " + channel + "/" + message + ".");
        }
        return pattern.asText();
    }

    /**
     * Validates a published payload against the message's declared schema.
     *
     * @return the validation failures, empty when the payload conforms
     */
    public List<String> validatePayload(String channel, String message, String payload) {
        JsonNode schema = resolve(messageNode(channel, message).path("payload"));

        // The document itself is the schema resource, with a $ref at the root pointing at the part
        // under test - so '#/components/schemas/Money' resolves exactly as it does in the file.
        ObjectNode rooted = document.deepCopy();
        rooted.setAll((ObjectNode) schema.deepCopy());

        // typeLoose off: "1000" must not satisfy an integer. An amount that arrived as a string
        // would be accepted by a lenient validator and rejected by a Go consumer.
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().typeLoose(false).build();
        JsonSchema compiled = schemas.getSchema(rooted, config);

        JsonNode parsed;
        try {
            parsed = JSON.readTree(payload);
        } catch (IOException notJson) {
            return List.of("The published payload is not JSON: " + notJson.getMessage());
        }

        List<String> failures = new ArrayList<>();
        for (ValidationMessage failure : compiled.validate(parsed)) {
            failures.add(failure.getMessage());
        }
        return failures;
    }

    private JsonNode messageNode(String channel, String message) {
        JsonNode node = document.path("channels").path(channel).path("messages").path(message);
        if (node.isMissingNode()) {
            throw new IllegalArgumentException(
                    "The contract declares no message " + message + " on channel " + channel + ".");
        }
        return resolve(node);
    }

    private JsonNode resolve(JsonNode node) {
        JsonNode ref = node.path("$ref");
        if (ref.isMissingNode()) {
            return node;
        }
        JsonNode target = document.at(ref.asText().substring(1));
        if (target.isMissingNode()) {
            throw new IllegalStateException("The contract has a dangling reference: " + ref.asText());
        }
        return resolve(target);
    }
}
