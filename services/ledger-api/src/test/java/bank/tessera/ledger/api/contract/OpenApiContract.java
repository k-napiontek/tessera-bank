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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code contracts/openapi/ledger-core.yaml}, loaded and made assertable.
 *
 * <p>The document is the source of truth, so this class reads it rather than restating it. Nothing
 * here hard-codes a field name, a status or a pattern: the operations, their request bodies and
 * their responses all come from the file, and a schema that changes there changes what this
 * validates without anyone editing a test.
 *
 * <p><strong>Why a JSON Schema validator and not an OpenAPI one.</strong> OpenAPI 3.1 schemas are
 * JSON Schema 2020-12, and this document uses it - {@code type: [string, 'null']} is 2020-12 and
 * cannot be expressed in 3.0. The OpenAPI-specific validators in the Java ecosystem still parse 3.1
 * by converting it down to 3.0, which loses exactly those constructs. Validating against a
 * downgraded copy of the contract would be a test that agrees with itself.
 *
 * <p>The whole document is handed to the validator as the schema resource, with a {@code $ref}
 * pointing at the part being checked, so internal references resolve the way they do in the file.
 * Keywords that are OpenAPI's rather than JSON Schema's - {@code paths}, {@code info} - are not
 * schema keywords and are ignored.
 */
public final class OpenApiContract {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JsonNode document;
    private final JsonSchemaFactory schemas;
    private final Map<String, Operation> operationsById = new HashMap<>();

    public OpenApiContract() {
        Path contracts = Path.of(System.getProperty(
                "tessera.contracts.dir", Path.of("..", "..", "contracts").toString()));
        Path file = contracts.resolve("openapi").resolve("ledger-core.yaml");
        try {
            this.document = YAML.readTree(Files.readAllBytes(file));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "Cannot read the OpenAPI contract at " + file.toAbsolutePath(), unreadable);
        }
        this.schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        index();
    }

    private void index() {
        JsonNode paths = document.path("paths");
        for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
            String template = it.next();
            JsonNode methods = paths.path(template);
            for (Iterator<String> m = methods.fieldNames(); m.hasNext(); ) {
                String method = m.next();
                JsonNode operation = methods.path(method);
                String operationId = operation.path("operationId").asText(null);
                if (operationId != null) {
                    operationsById.put(
                            operationId, new Operation(operationId, method.toUpperCase(), template, operation));
                }
            }
        }
    }

    /** Every operation the document declares. The contract test must exercise all of them. */
    public Set<String> operationIds() {
        return new LinkedHashSet<>(operationsById.keySet());
    }

    public Operation operation(String operationId) {
        Operation operation = operationsById.get(operationId);
        if (operation == null) {
            throw new IllegalArgumentException(
                    "The contract declares no operation " + operationId + ". Declared: " + operationIds());
        }
        return operation;
    }

    /**
     * Validates a body against the schema the document declares for that operation and status.
     *
     * @return the validation failures, empty when the body conforms
     */
    public List<String> validateResponse(String operationId, int status, String body) {
        JsonNode schemaRef = operation(operationId)
                .node()
                .path("responses")
                .path(String.valueOf(status));
        if (schemaRef.isMissingNode()) {
            return List.of("The contract declares no " + status + " response for " + operationId + ".");
        }
        JsonNode schema = firstContentSchema(schemaRef);
        return schema == null ? List.of() : validate(schema, body);
    }

    /** Validates a request body against the operation's declared request schema. */
    public List<String> validateRequest(String operationId, String body) {
        JsonNode requestBody = operation(operationId).node().path("requestBody");
        if (requestBody.isMissingNode()) {
            return List.of();
        }
        JsonNode schema = firstContentSchema(requestBody);
        return schema == null ? List.of() : validate(schema, body);
    }

    /** True when the operation requires an {@code Idempotency-Key}, per the document itself. */
    public boolean requiresIdempotencyKey(String operationId) {
        for (JsonNode parameter : operation(operationId).node().path("parameters")) {
            if (parameter.path("$ref").asText("").endsWith("/IdempotencyKey")) {
                return true;
            }
        }
        return false;
    }

    /** Resolves a response or requestBody node down to the schema of its single media type. */
    private JsonNode firstContentSchema(JsonNode node) {
        JsonNode resolved = resolve(node);
        JsonNode content = resolved.path("content");
        if (content.isMissingNode() || !content.fieldNames().hasNext()) {
            return null;
        }
        return content.path(content.fieldNames().next()).path("schema");
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
        return target;
    }

    private List<String> validate(JsonNode schemaNode, String body) {
        // The document itself is the schema resource, with a $ref at the root pointing at the part
        // under test. That is what lets '#/components/schemas/Money' resolve exactly as it does in
        // the file, rather than being copied out and losing its references.
        ObjectNode rooted = document.deepCopy();
        rooted.setAll((ObjectNode) schemaNode.deepCopy());

        // typeLoose off: "1000" must not satisfy an integer. A ledger that accepted a stringly
        // typed amount would be one Jackson setting away from a decimal on the wire.
        SchemaValidatorsConfig config =
                SchemaValidatorsConfig.builder().typeLoose(false).build();
        JsonSchema schema = schemas.getSchema(rooted, config);

        JsonNode parsed;
        try {
            parsed = body == null || body.isBlank() ? JSON.nullNode() : JSON.readTree(body);
        } catch (IOException notJson) {
            return List.of("The response body is not JSON: " + notJson.getMessage());
        }

        List<String> failures = new ArrayList<>();
        for (ValidationMessage message : schema.validate(parsed)) {
            failures.add(message.getMessage());
        }
        return failures;
    }

    /** One declared operation: its id, its method and the path template it lives at. */
    public record Operation(String operationId, String method, String pathTemplate, JsonNode node) {}
}
