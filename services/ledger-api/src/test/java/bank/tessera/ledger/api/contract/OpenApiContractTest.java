package bank.tessera.ledger.api.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import bank.tessera.ledger.api.LedgerApiTest;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * The implementation cannot drift from its contract. REQ-API-002.
 *
 * <p>One walkthrough exercises every operation the document declares, and every request and response
 * that passes through is validated against the schema the document declares for it. Then the
 * coverage assertion at the end fails if any {@code operationId} was never reached - because a
 * contract test that only checks the operations somebody remembered to call is a contract test with
 * a hole in it exactly where the drift will be.
 *
 * <p><strong>Traceability to the canonical data model</strong>, as WP-02's conformance instruction
 * requires. Each field the schemas assert traces to
 * {@code docs/architecture/canonical-data-model.md}:
 *
 * <table>
 *   <caption>Contract field to canonical model field</caption>
 *   <tr><th>Contract</th><th>Canonical model</th><th>What the schema enforces here</th></tr>
 *   <tr><td>{@code Money.amountMinor}</td><td>§2 {@code amountMinor}</td>
 *       <td>{@code integer/int64} - a decimal or a quoted number fails</td></tr>
 *   <tr><td>{@code Money.currency}</td><td>§2 {@code currency}</td>
 *       <td>{@code ^[A-Z]{3}$}</td></tr>
 *   <tr><td>{@code Account.accountRef}</td><td>§1 identifiers, §3</td>
 *       <td>{@code ^TB[0-9A-Z]{14}$}, 16 characters</td></tr>
 *   <tr><td>{@code Account.customerRef}</td><td>§3</td><td>{@code ^CU[0-9]{10}$}</td></tr>
 *   <tr><td>{@code Account.accountType}</td><td>§3 account types</td>
 *       <td>the five-value enum, so a sixth type cannot appear silently</td></tr>
 *   <tr><td>{@code Transfer.transferRef}</td><td>§1, §5</td><td>{@code ^TB[0-9]{18}$}</td></tr>
 *   <tr><td>{@code Movement.movementRef}</td><td>§4</td>
 *       <td>{@code ^TB[0-9]{18}-[0-9]{2}$} - the transfer reference and the leg</td></tr>
 *   <tr><td>{@code Movement.direction}</td><td>§4</td>
 *       <td>{@code DEBIT|CREDIT} - direction carries the sign, the amount never does</td></tr>
 *   <tr><td>{@code Transfer.movements}</td><td>§8 invariant 1</td>
 *       <td>{@code minItems: 2, maxItems: 2} - double entry, in the schema</td></tr>
 *   <tr><td>{@code Hold.status}</td><td>§6</td><td>{@code PLACED|CAPTURED|RELEASED|EXPIRED}</td></tr>
 *   <tr><td>{@code postedAt}, {@code placedAt}</td><td>§1 time</td>
 *       <td>RFC 3339 with {@code Z}; millisecond precision is asserted separately</td></tr>
 * </table>
 *
 * <p>{@code additionalProperties: false} on every response schema is what makes this bite in the
 * other direction: a field the implementation adds and the document does not declare fails here.
 */
class OpenApiContractTest extends LedgerApiTest {

    private final OpenApiContract contract = new OpenApiContract();
    private final Set<String> exercised = new LinkedHashSet<>();

    /** Performs the exchange and holds both sides to the schemas the document declares. */
    private MvcResult verify(String operationId, RequestBuilder request, String requestBody)
            throws Exception {
        if (requestBody != null) {
            assertThat(contract.validateRequest(operationId, requestBody))
                    .describedAs("request body of %s", operationId)
                    .isEmpty();
        }

        MvcResult result = mvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();

        assertThat(contract.validateResponse(operationId, status, body))
                .describedAs("%s responded %d with %s", operationId, status, body)
                .isEmpty();

        exercised.add(operationId);
        return result;
    }

    private String openAccount(String type) throws Exception {
        String reference = freshAccountReference();
        String body = """
                {"accountRef":"%s","customerRef":"CU0000000001","accountType":"%s","currency":"PLN"}
                """
                .formatted(reference, type);
        verify(
                "openAccount",
                post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body),
                body);
        return reference;
    }

    private String reference(MvcResult result, String field) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).path(field).asText();
    }

    @Test
    @DisplayName("every operation in the document is exercised, and every exchange conforms to it")
    void theImplementationMatchesTheContract() throws Exception {
        String vault = openAccount("ASSET");
        String alice = openAccount("LIABILITY");
        String bob = openAccount("LIABILITY");

        // --- fund, then transfer, so the statement has something to page ---------------------
        String fund = """
                {"debitAccountRef":"%s","creditAccountRef":"%s",
                 "amount":{"amountMinor":50000,"currency":"PLN"}}
                """
                .formatted(vault, alice);
        verify(
                "createTransfer",
                post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fund),
                fund);

        String transferBody = """
                {"debitAccountRef":"%s","creditAccountRef":"%s",
                 "amount":{"amountMinor":4000,"currency":"PLN"},"reference":"INVOICE 2026-08-19"}
                """
                .formatted(alice, bob);
        MvcResult posted = verify(
                "createTransfer",
                post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody),
                transferBody);
        String transferRef = reference(posted, "transferRef");

        verify("getTransfer", get("/v1/transfers/{ref}", transferRef), null);
        verify("getAccount", get("/v1/accounts/{ref}", alice), null);
        verify("getBalance", get("/v1/accounts/{ref}/balance", alice), null);
        verify(
                "getStatement",
                get("/v1/accounts/{ref}/statement", alice)
                        .param("from", "2026-01-01")
                        .param("to", "2026-12-31")
                        .param("limit", "1"),
                null);

        // --- reverse it -----------------------------------------------------------------------
        String reversal = "{\"reason\":\"keyed in error\"}";
        verify(
                "reverseTransfer",
                post("/v1/transfers/{ref}/reversals", transferRef)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversal),
                reversal);

        // --- holds ----------------------------------------------------------------------------
        String holdBody = "{\"amount\":{\"amountMinor\":10000,\"currency\":\"PLN\"}}";
        MvcResult placed = verify(
                "placeHold",
                post("/v1/accounts/{ref}/holds", alice)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody),
                holdBody);
        String capturable = reference(placed, "holdRef");

        MvcResult releasable = verify(
                "placeHold",
                post("/v1/accounts/{ref}/holds", alice)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody),
                holdBody);

        verify("listHolds", get("/v1/accounts/{ref}/holds", alice), null);

        String captureBody = """
                {"creditAccountRef":"%s","amount":{"amountMinor":10000,"currency":"PLN"}}
                """
                .formatted(bob);
        verify(
                "captureHold",
                post("/v1/holds/{ref}/capture", capturable)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(captureBody),
                captureBody);

        verify(
                "releaseHold",
                post("/v1/holds/{ref}/release", reference(releasable, "holdRef"))
                        .header("Idempotency-Key", freshIdempotencyKey()),
                null);

        // --- the coverage assertion -------------------------------------------------------------
        assertThat(exercised)
                .describedAs("every operationId the contract declares must be exercised")
                .containsExactlyInAnyOrderElementsOf(contract.operationIds());
    }

    @Test
    @DisplayName("every error response conforms to the Problem schema the document declares")
    void errorsConformToo() throws Exception {
        verify("getAccount", get("/v1/accounts/{ref}", "TB00000000999999"), null);
        verify("getTransfer", get("/v1/transfers/{ref}", "TB202608190000099999"), null);

        String unknown = """
                {"debitAccountRef":"TB00000000999999","creditAccountRef":"TB00000000999998",
                 "amount":{"amountMinor":100,"currency":"PLN"}}
                """;
        verify(
                "createTransfer",
                post("/v1/transfers")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknown),
                unknown);
    }

    @Test
    @DisplayName("the document requires an Idempotency-Key on exactly the five money-moving operations")
    void theHeaderIsRequiredWhereTheContractSaysItIs() {
        // Read from the document, not restated here, so this stays true if the contract changes.
        Set<String> requiring = new LinkedHashSet<>();
        for (String operationId : contract.operationIds()) {
            if (contract.requiresIdempotencyKey(operationId)) {
                requiring.add(operationId);
            }
        }

        assertThat(requiring)
                .containsExactlyInAnyOrder(
                        "createTransfer", "reverseTransfer", "placeHold", "captureHold", "releaseHold");
    }
}
