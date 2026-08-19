package bank.tessera.ledger.api.idempotency;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * A digest of the request an idempotency key was used for.
 *
 * <p>Comparing fingerprints rather than bodies is what lets the store answer "same key, different
 * request" without ever holding what the client sent. The digest covers the method, the resolved
 * path and the body, because all three are part of what was asked: the same key on
 * {@code /holds/A/release} and {@code /holds/B/release} is two different operations, and a
 * fingerprint over the body alone would call them equal.
 *
 * <p><strong>The body is canonicalised first.</strong> A client that retries by re-serialising its
 * request can reorder the fields or change the whitespace without meaning anything by it, and a
 * digest over the raw bytes would call that a conflict and refuse a legitimate retry. Object keys
 * are sorted recursively; array order is preserved, because in JSON an array's order is meaning
 * rather than presentation.
 *
 * <p>A body that is not JSON is digested as it arrived. It is not this class's job to decide the
 * request is malformed - the controller does that, and it does it properly.
 */
public final class RequestFingerprint {

    private final ObjectMapper json;

    public RequestFingerprint(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    /** @param body the raw request body, empty for an operation that carries none */
    public String of(String method, String path, byte[] body) {
        MessageDigest digest = sha256();
        digest.update(method.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(path.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(canonical(body));
        return HexFormat.of().formatHex(digest.digest());
    }

    private byte[] canonical(byte[] body) {
        if (body == null || body.length == 0) {
            return new byte[0];
        }
        try {
            return sort(json.readTree(body)).toString().getBytes(StandardCharsets.UTF_8);
        } catch (java.io.IOException notJson) {
            return body;
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            names.sort(String::compareTo);
            ObjectNode sorted = json.createObjectNode();
            for (String name : names) {
                sorted.set(name, sort(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = json.createArrayNode();
            node.forEach(element -> sorted.add(sort(element)));
            return sorted;
        }
        return node;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256. If this ever throws, the platform is broken
            // in a way that no fallback here would make safe.
            throw new IllegalStateException("SHA-256 is not available on this JVM.", impossible);
        }
    }
}
