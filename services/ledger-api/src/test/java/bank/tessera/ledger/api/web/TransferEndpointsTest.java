package bank.tessera.ledger.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.tessera.ledger.api.LedgerApiTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Money movement over HTTP, and the idempotency that makes a retry safe.
 *
 * <p>The defining test is {@link #aReplayMovesMoneyOnceAndReturnsTheOriginal}: a client whose
 * connection dropped mid-transfer retries, and must get the first answer back rather than a second
 * transfer.
 */
class TransferEndpointsTest extends LedgerApiTest {

    private String vault;
    private String alice;
    private String bob;

    @BeforeEach
    void openAccounts() throws Exception {
        vault = open(freshAccountReference(), "ASSET");
        alice = open(freshAccountReference(), "LIABILITY");
        bob = open(freshAccountReference(), "LIABILITY");
        // A deposit: the vault is an ASSET and rises on a debit, the customer account is a LIABILITY
        // and rises on a credit.
        transfer(vault, alice, 500_00, freshIdempotencyKey()).andExpect(status().isCreated());
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

    private String transferBody(String debit, String credit, long minor) {
        return """
                {
                  "debitAccountRef": "%s",
                  "creditAccountRef": "%s",
                  "amount": { "amountMinor": %d, "currency": "PLN" }
                }
                """
                .formatted(debit, credit, minor);
    }

    private org.springframework.test.web.servlet.ResultActions transfer(
            String debit, String credit, long minor, String key) throws Exception {
        return mvc.perform(post("/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(debit, credit, minor)));
    }

    private long bookedBalanceOf(String account) throws Exception {
        MvcResult result = mvc.perform(get("/v1/accounts/{ref}/balance", account))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString())
                .path("booked")
                .path("amountMinor")
                .asLong();
    }

    @Test
    @DisplayName("a transfer posts, returns 201, and both movements are on the document")
    void aTransferPosts() throws Exception {
        transfer(alice, bob, 40_00, freshIdempotencyKey())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferRef").value(Matchers.matchesPattern("^TB[0-9]{18}$")))
                .andExpect(jsonPath("$.debitAccountRef").value(alice))
                .andExpect(jsonPath("$.creditAccountRef").value(bob))
                .andExpect(jsonPath("$.amount.amountMinor").value(40_00))
                .andExpect(jsonPath("$.amount.currency").value("PLN"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.movements", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.movements[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.movements[0].legNo").value(1))
                .andExpect(jsonPath("$.movements[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.movements[1].legNo").value(2))
                .andExpect(jsonPath("$.movements[0].movementRef")
                        .value(Matchers.matchesPattern("^TB[0-9]{18}-01$")))
                .andExpect(jsonPath("$.reversesTransferRef").doesNotExist());

        assertThat(bookedBalanceOf(alice)).isEqualTo(460_00L);
        assertThat(bookedBalanceOf(bob)).isEqualTo(40_00L);
    }

    @Test
    @DisplayName("a replay moves money once and returns the original response, byte for byte")
    void aReplayMovesMoneyOnceAndReturnsTheOriginal() throws Exception {
        String key = freshIdempotencyKey();

        MvcResult first = transfer(alice, bob, 25_00, key).andExpect(status().isCreated()).andReturn();
        String firstBody = first.getResponse().getContentAsString();
        long afterFirst = bookedBalanceOf(alice);

        MvcResult replay = transfer(alice, bob, 25_00, key).andExpect(status().isOk()).andReturn();

        // Byte for byte, not "equivalent". A re-rendered response would pick up anything that had
        // changed since - a balance, a status - and the client would get a different document for
        // the same request, which is exactly the promise the key makes.
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(firstBody);
        assertThat(bookedBalanceOf(alice)).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("a replay tolerates reordered fields and different whitespace")
    void aReplayIsNotConfusedByFormatting() throws Exception {
        String key = freshIdempotencyKey();
        transfer(alice, bob, 10_00, key).andExpect(status().isCreated());

        String reordered = """
                {"amount":{"currency":"PLN","amountMinor":1000},"creditAccountRef":"%s","debitAccountRef":"%s"}
                """
                .formatted(bob, alice);

        // A client that retries by re-serialising its request means nothing by the new field order.
        // Fingerprinting the raw bytes would call this a conflict and refuse a legitimate retry.
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reordered))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the same key with a changed amount is 409, and no second transfer posts")
    void theSameKeyWithADifferentBodyConflicts() throws Exception {
        String key = freshIdempotencyKey();
        transfer(alice, bob, 30_00, key).andExpect(status().isCreated());
        long afterFirst = bookedBalanceOf(alice);

        transfer(alice, bob, 31_00, key)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/idempotency-conflict"))
                .andExpect(jsonPath("$.detail", Matchers.not(Matchers.containsString(key))));

        assertThat(bookedBalanceOf(alice)).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("a rejected transfer stays retryable under the same key")
    void aRejectedTransferDoesNotBurnTheKey() throws Exception {
        String key = freshIdempotencyKey();

        // More than the account holds, and the account forbids an overdraft.
        transfer(alice, bob, 10_000_00, key).andExpect(status().isUnprocessableEntity());

        // Storing the 422 would answer this retry forever, and the client could never correct its
        // mistake without inventing a new key for a request it considers the same one.
        transfer(alice, bob, 10_00, key).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("a missing or undersized Idempotency-Key is 400 and nothing is posted")
    void theKeyIsRequired() throws Exception {
        long before = bookedBalanceOf(alice);

        mvc.perform(post("/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice, bob, 1_00)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/validation-failed"));

        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", "too-short")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice, bob, 1_00)))
                .andExpect(status().isBadRequest());

        assertThat(bookedBalanceOf(alice)).isEqualTo(before);
    }

    @Test
    @DisplayName("a transfer beyond the balance is 422 insufficient-funds and posts nothing")
    void anOverdraftIsRefused() throws Exception {
        long before = bookedBalanceOf(alice);

        transfer(alice, bob, 10_000_00, freshIdempotencyKey())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/insufficient-funds"));

        assertThat(bookedBalanceOf(alice)).isEqualTo(before);
    }

    @Test
    @DisplayName("a transfer naming the same account twice is 422")
    void aSelfTransferIsRefused() throws Exception {
        transfer(alice, alice, 1_00, freshIdempotencyKey())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/not-actionable"));
    }

    @Test
    @DisplayName("a transfer against an unknown account is 404")
    void anUnknownAccountIs404() throws Exception {
        transfer(alice, "TB00000000999999", 1_00, freshIdempotencyKey())
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a transfer can be fetched and reversed, and the original then reports REVERSED")
    void aTransferCanBeReversed() throws Exception {
        MvcResult posted =
                transfer(alice, bob, 20_00, freshIdempotencyKey()).andExpect(status().isCreated()).andReturn();
        String transferRef =
                json.readTree(posted.getResponse().getContentAsString()).path("transferRef").asText();

        mvc.perform(post("/v1/transfers/{ref}/reversals", transferRef)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"keyed in error\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversesTransferRef").value(transferRef))
                .andExpect(jsonPath("$.debitAccountRef").value(bob))
                .andExpect(jsonPath("$.creditAccountRef").value(alice));

        mvc.perform(get("/v1/transfers/{ref}", transferRef))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));

        // The reversal put the money back, so the account is where it started.
        assertThat(bookedBalanceOf(alice)).isEqualTo(500_00L);
    }

    @Test
    @DisplayName("reversing twice is 409")
    void reversingTwiceIsRefused() throws Exception {
        MvcResult posted =
                transfer(alice, bob, 5_00, freshIdempotencyKey()).andExpect(status().isCreated()).andReturn();
        String transferRef =
                json.readTree(posted.getResponse().getContentAsString()).path("transferRef").asText();

        mvc.perform(post("/v1/transfers/{ref}/reversals", transferRef)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"first\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/v1/transfers/{ref}/reversals", transferRef)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/conflicting-state"));
    }

    @Test
    @DisplayName("a reversal without a reason is 400")
    void aReversalNeedsAReason() throws Exception {
        MvcResult posted =
                transfer(alice, bob, 5_00, freshIdempotencyKey()).andExpect(status().isCreated()).andReturn();
        String transferRef =
                json.readTree(posted.getResponse().getContentAsString()).path("transferRef").asText();

        mvc.perform(post("/v1/transfers/{ref}/reversals", transferRef)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
