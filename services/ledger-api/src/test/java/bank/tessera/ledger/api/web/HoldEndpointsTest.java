package bank.tessera.ledger.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.tessera.ledger.api.LedgerApiTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Holds over HTTP: placing, capturing, releasing, and what each does to the two balances. */
class HoldEndpointsTest extends LedgerApiTest {

    private String alice;
    private String bob;

    @BeforeEach
    void openAccounts() throws Exception {
        String vault = open(freshAccountReference(), "ASSET");
        alice = open(freshAccountReference(), "LIABILITY");
        bob = open(freshAccountReference(), "LIABILITY");
        transfer(vault, alice, 500_00).andExpect(status().isCreated());
    }

    private String open(String reference, String type) throws Exception {
        mvc.perform(post("/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountRef":"%s","customerRef":"CU0000000001",
                                 "accountType":"%s","currency":"PLN"}
                                """
                                .formatted(reference, type)))
                .andExpect(status().isCreated());
        return reference;
    }

    private ResultActions transfer(String debit, String credit, long minor) throws Exception {
        return mvc.perform(post("/v1/transfers")
                .header("Idempotency-Key", freshIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"debitAccountRef":"%s","creditAccountRef":"%s",
                         "amount":{"amountMinor":%d,"currency":"PLN"}}
                        """
                        .formatted(debit, credit, minor)));
    }

    private String placeHold(long minor, String key) throws Exception {
        MvcResult result = mvc.perform(post("/v1/accounts/{ref}/holds", alice)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"amountMinor\":%d,\"currency\":\"PLN\"}}".formatted(minor)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("holdRef").asText();
    }

    private long[] balancesOf(String account) throws Exception {
        MvcResult result = mvc.perform(get("/v1/accounts/{ref}/balance", account))
                .andExpect(status().isOk())
                .andReturn();
        var node = json.readTree(result.getResponse().getContentAsString());
        return new long[] {
            node.path("booked").path("amountMinor").asLong(),
            node.path("available").path("amountMinor").asLong()
        };
    }

    @Test
    @DisplayName("a hold reduces available balance and leaves booked alone")
    void aHoldReservesWithoutMoving() throws Exception {
        String holdRef = placeHold(120_00, freshIdempotencyKey());

        assertThat(holdRef).matches("^HL[0-9]{18}$");
        assertThat(balancesOf(alice)).containsExactly(500_00L, 380_00L);

        mvc.perform(get("/v1/accounts/{ref}/holds", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].holdRef").value(holdRef))
                .andExpect(jsonPath("$[0].status").value("PLACED"))
                .andExpect(jsonPath("$[0].amount.amountMinor").value(120_00))
                .andExpect(jsonPath("$[0].capturedByTransferRef").doesNotExist());
    }

    @Test
    @DisplayName("capturing posts a transfer, clears the hold, and moves booked balance once")
    void captureMovesTheMoneyOnce() throws Exception {
        String holdRef = placeHold(120_00, freshIdempotencyKey());

        MvcResult captured = mvc.perform(post("/v1/holds/{ref}/capture", holdRef)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"creditAccountRef":"%s","amount":{"amountMinor":12000,"currency":"PLN"}}
                                """
                                .formatted(bob)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitAccountRef").value(alice))
                .andExpect(jsonPath("$.creditAccountRef").value(bob))
                .andReturn();

        String transferRef =
                json.readTree(captured.getResponse().getContentAsString()).path("transferRef").asText();

        // Booked fell by the captured amount, and available fell by the same - not twice. Leaving
        // the hold PLACED would charge the customer in both figures for one payment.
        assertThat(balancesOf(alice)).containsExactly(380_00L, 380_00L);

        mvc.perform(get("/v1/accounts/{ref}/holds", alice).param("includeInactive", "true"))
                .andExpect(jsonPath("$[0].status").value("CAPTURED"))
                .andExpect(jsonPath("$[0].capturedByTransferRef").value(transferRef));
        mvc.perform(get("/v1/accounts/{ref}/holds", alice))
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("releasing returns the reservation and moves no money")
    void releasingReturnsTheReservation() throws Exception {
        String holdRef = placeHold(75_00, freshIdempotencyKey());

        mvc.perform(post("/v1/holds/{ref}/release", holdRef)
                        .header("Idempotency-Key", freshIdempotencyKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        assertThat(balancesOf(alice)).containsExactly(500_00L, 500_00L);
    }

    @Test
    @DisplayName("releasing replays under the same key rather than failing on a captured hold")
    void releasingIsIdempotent() throws Exception {
        String holdRef = placeHold(10_00, freshIdempotencyKey());
        String key = freshIdempotencyKey();

        MvcResult first = mvc.perform(post("/v1/holds/{ref}/release", holdRef).header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andReturn();

        // No body at all, so the fingerprint is the method and the path - which is enough, because
        // the operation names exactly one hold and there is nothing else a client could vary.
        MvcResult replay = mvc.perform(post("/v1/holds/{ref}/release", holdRef).header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("releasing a released hold under a new key is 409")
    void releasingTwiceUnderDifferentKeysIsRefused() throws Exception {
        String holdRef = placeHold(10_00, freshIdempotencyKey());
        mvc.perform(post("/v1/holds/{ref}/release", holdRef).header("Idempotency-Key", freshIdempotencyKey()))
                .andExpect(status().isOk());

        mvc.perform(post("/v1/holds/{ref}/release", holdRef).header("Idempotency-Key", freshIdempotencyKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/conflicting-state"));
    }

    @Test
    @DisplayName("capturing more than was reserved is 422")
    void anOverCaptureIsRefused() throws Exception {
        String holdRef = placeHold(50_00, freshIdempotencyKey());

        mvc.perform(post("/v1/holds/{ref}/capture", holdRef)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"creditAccountRef":"%s","amount":{"amountMinor":5001,"currency":"PLN"}}
                                """
                                .formatted(bob)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/not-actionable"));
    }

    @Test
    @DisplayName("an unknown hold is 404 and an unknown account is 404")
    void unknownReferencesAre404() throws Exception {
        mvc.perform(post("/v1/holds/{ref}/release", "HL202608190000099999")
                        .header("Idempotency-Key", freshIdempotencyKey()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/v1/accounts/{ref}/holds", "TB00000000999999")
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"amountMinor\":100,\"currency\":\"PLN\"}}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a hold in the wrong currency is 422")
    void aWrongCurrencyHoldIsRefused() throws Exception {
        mvc.perform(post("/v1/accounts/{ref}/holds", alice)
                        .header("Idempotency-Key", freshIdempotencyKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":{\"amountMinor\":100,\"currency\":\"EUR\"}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/currency-mismatch"));
    }
}
