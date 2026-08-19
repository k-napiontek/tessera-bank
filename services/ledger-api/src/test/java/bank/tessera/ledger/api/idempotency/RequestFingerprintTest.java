package bank.tessera.ledger.api.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What counts as "the same request" for the purpose of a retry.
 *
 * <p>Every assertion here is a decision about when a client is retrying and when it is making a
 * different request under a key it has already used. Get it too strict and a legitimate retry is
 * refused with a 409; get it too loose and two different transfers share one key and the second is
 * answered with the first one's response.
 */
class RequestFingerprintTest {

    private final RequestFingerprint fingerprints = new RequestFingerprint(new ObjectMapper());

    private String of(String method, String path, String body) {
        return fingerprints.of(method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the same request fingerprints the same way twice")
    void itIsStable() {
        String body = "{\"amount\":{\"amountMinor\":1000,\"currency\":\"PLN\"}}";

        assertThat(of("POST", "/v1/transfers", body)).isEqualTo(of("POST", "/v1/transfers", body));
    }

    @Test
    @DisplayName("reordered object keys are the same request")
    void keyOrderDoesNotMatter() {
        // A client that retries by re-serialising means nothing by the new field order. Digesting
        // the raw bytes would call this a conflict and refuse a retry the key exists to permit.
        assertThat(of("POST", "/v1/transfers", "{\"a\":1,\"b\":{\"c\":2,\"d\":3}}"))
                .isEqualTo(of("POST", "/v1/transfers", "{\"b\":{\"d\":3,\"c\":2},\"a\":1}"));
    }

    @Test
    @DisplayName("whitespace is not part of the request")
    void whitespaceDoesNotMatter() {
        assertThat(of("POST", "/v1/transfers", "{\"a\":1}"))
                .isEqualTo(of("POST", "/v1/transfers", "{\n  \"a\" : 1\n}\n"));
    }

    @Test
    @DisplayName("array order is part of the request, because in JSON it means something")
    void arrayOrderMatters() {
        assertThat(of("POST", "/v1/transfers", "{\"legs\":[1,2]}"))
                .isNotEqualTo(of("POST", "/v1/transfers", "{\"legs\":[2,1]}"));
    }

    @Test
    @DisplayName("a changed value is a different request")
    void valuesMatter() {
        assertThat(of("POST", "/v1/transfers", "{\"amountMinor\":1000}"))
                .isNotEqualTo(of("POST", "/v1/transfers", "{\"amountMinor\":1001}"));
    }

    @Test
    @DisplayName("the path is part of the request")
    void thePathMatters() {
        // Releasing hold A and releasing hold B carry no body at all. Fingerprinting the body alone
        // would make them the same request, and one key would release the wrong reservation.
        assertThat(of("POST", "/v1/holds/HL202608190000000001/release", ""))
                .isNotEqualTo(of("POST", "/v1/holds/HL202608190000000002/release", ""));
    }

    @Test
    @DisplayName("the method is part of the request")
    void theMethodMatters() {
        assertThat(of("POST", "/v1/transfers", "{}")).isNotEqualTo(of("PUT", "/v1/transfers", "{}"));
    }

    @Test
    @DisplayName("an absent body and an empty body are the same, and both are fingerprintable")
    void anAbsentBodyIsFine() {
        assertThat(of("POST", "/v1/holds/HL202608190000000001/release", null))
                .isEqualTo(of("POST", "/v1/holds/HL202608190000000001/release", ""));
    }

    @Test
    @DisplayName("a body that is not JSON is digested as it arrived, not rejected")
    void nonJsonIsDigestedAsIs() {
        // Deciding a request is malformed is the controller's job, and it does it with a message the
        // client can act on. This class only has to be deterministic.
        assertThat(of("POST", "/v1/transfers", "not json at all"))
                .isEqualTo(of("POST", "/v1/transfers", "not json at all"));
        assertThat(of("POST", "/v1/transfers", "not json at all"))
                .isNotEqualTo(of("POST", "/v1/transfers", "also not json"));
    }

    @Test
    @DisplayName("a fingerprint is 64 hex characters, which is what the column holds")
    void itIsASha256Hex() {
        assertThat(of("POST", "/v1/transfers", "{}")).matches("^[0-9a-f]{64}$");
    }
}
