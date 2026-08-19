package bank.tessera.ledger.api.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.tessera.ledger.api.LedgerApiTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Opening an account, and reading it back over HTTP. */
class AccountEndpointsTest extends LedgerApiTest {

    private String openAccount(String reference, String currency) throws Exception {
        String body = """
                {
                  "accountRef": "%s",
                  "customerRef": "CU0000000001",
                  "accountType": "LIABILITY",
                  "currency": "%s",
                  "openedDate": "2026-03-01"
                }
                """
                .formatted(reference, currency);
        mvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        return reference;
    }

    @Test
    @DisplayName("an account opens at the supplied reference with both balances at zero")
    void openingReturnsTheAccount() throws Exception {
        String reference = freshAccountReference();
        String body = """
                {
                  "accountRef": "%s",
                  "customerRef": "CU0000000001",
                  "accountType": "LIABILITY",
                  "currency": "PLN",
                  "openedDate": "2026-03-01"
                }
                """
                .formatted(reference);

        mvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountRef").value(reference))
                .andExpect(jsonPath("$.customerRef").value("CU0000000001"))
                .andExpect(jsonPath("$.accountType").value("LIABILITY"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.openedDate").value("2026-03-01"))
                .andExpect(jsonPath("$.bookedBalance.amountMinor").value(0))
                .andExpect(jsonPath("$.bookedBalance.currency").value("PLN"))
                // Absent until the first movement posts. An explicit null and an absent field say
                // different things, and non_null inclusion is what keeps them different.
                .andExpect(jsonPath("$.lastMovementDate").doesNotExist());
    }

    @Test
    @DisplayName("opening the same reference twice is 409 with a Problem document")
    void openingTwiceConflicts() throws Exception {
        String reference = openAccount(freshAccountReference(), "PLN");
        String body = """
                {
                  "accountRef": "%s",
                  "customerRef": "CU0000000001",
                  "accountType": "LIABILITY",
                  "currency": "PLN"
                }
                """
                .formatted(reference);

        mvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/account-already-open"));
    }

    @Test
    @DisplayName("an invalid request body is 400 and names the offending fields")
    void anInvalidBodyIsRejected() throws Exception {
        String body = """
                {
                  "accountRef": "NOPE",
                  "customerRef": "CU0000000001",
                  "accountType": "BADGER",
                  "currency": "pln"
                }
                """;

        mvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/validation-failed"))
                .andExpect(jsonPath("$.violations", Matchers.hasSize(3)));
    }

    @Test
    @DisplayName("money is minor units and a currency code, never a decimal")
    void moneyIsNeverADecimal() throws Exception {
        String reference = openAccount(freshAccountReference(), "PLN");

        mvc.perform(get("/v1/accounts/{ref}/balance", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.booked.amountMinor").isNumber())
                .andExpect(jsonPath("$.booked.currency").value("PLN"))
                // A serialiser reaching for Money.toPlainString would produce "0.00" here, and every
                // consumer downstream would start parsing decimals off the wire.
                .andExpect(content().string(Matchers.not(Matchers.containsString("0.00"))))
                .andExpect(jsonPath("$.asOf").value(Matchers.matchesPattern(
                        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")));
    }

    @Test
    @DisplayName("an unknown account is 404, not an empty account")
    void anUnknownAccountIs404() throws Exception {
        mvc.perform(get("/v1/accounts/{ref}", "TB00000000999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://problems.tesserabank.example/not-found"));
        mvc.perform(get("/v1/accounts/{ref}/balance", "TB00000000999999"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/accounts/{ref}/holds", "TB00000000999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a malformed account reference is 400, not 404")
    void aMalformedReferenceIs400() throws Exception {
        // The reference never matched the pattern, so there is nothing to look up. Answering 404
        // would tell the caller their well-formed reference is simply unknown, and they would retry.
        mvc.perform(get("/v1/accounts/{ref}", "not-an-account"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/validation-failed"));
    }

    @Test
    @DisplayName("an account with no holds returns an empty array")
    void holdsAreEmptyToBeginWith() throws Exception {
        String reference = openAccount(freshAccountReference(), "PLN");

        mvc.perform(get("/v1/accounts/{ref}/holds", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("an empty statement still states the range and both balances")
    void anEmptyStatementStillFoots() throws Exception {
        String reference = openAccount(freshAccountReference(), "PLN");

        mvc.perform(get("/v1/accounts/{ref}/statement", reference)
                        .param("from", "2026-01-01")
                        .param("to", "2026-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountRef").value(reference))
                .andExpect(jsonPath("$.from").value("2026-01-01"))
                .andExpect(jsonPath("$.to").value("2026-12-31"))
                .andExpect(jsonPath("$.openingBalance.amountMinor").value(0))
                .andExpect(jsonPath("$.closingBalance.amountMinor").value(0))
                .andExpect(jsonPath("$.movements", Matchers.hasSize(0)))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("an inverted statement range is 422, and a missing one is 400")
    void badStatementRangesAreRejected() throws Exception {
        String reference = openAccount(freshAccountReference(), "PLN");

        mvc.perform(get("/v1/accounts/{ref}/statement", reference)
                        .param("from", "2026-12-31")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/v1/accounts/{ref}/statement", reference).param("from", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
