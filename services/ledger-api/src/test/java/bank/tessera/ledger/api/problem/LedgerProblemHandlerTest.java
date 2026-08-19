package bank.tessera.ledger.api.problem;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import bank.tessera.ledger.application.AccountAlreadyOpenException;
import bank.tessera.ledger.application.AccountNotFoundException;
import bank.tessera.ledger.application.AlreadyReversedException;
import bank.tessera.ledger.application.NotActionableException;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CurrencyMismatchException;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftNotPermittedException;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.port.IdempotencyConflictException;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every failure path returns an RFC 9457 document, and none of them leaks anything.
 *
 * <p>Driven through a controller that exists only to throw, because the point is the handler rather
 * than any endpoint. Standalone MockMvc keeps it at milliseconds and off the container - what is
 * being asserted is a mapping, and a mapping does not need a database to be wrong.
 */
class LedgerProblemHandlerTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new LedgerProblemHandler())
                .build();
    }

    @Test
    @DisplayName("a missing account is 404 with a stable type URI")
    void notFoundIsMapped() throws Exception {
        mvc.perform(get("/throw/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://problems.tesserabank.example/not-found"))
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.instance").value("/throw/not-found"));
    }

    @Test
    @DisplayName("an overdraft breach is 422 insufficient-funds")
    void insufficientFundsIsMapped() throws Exception {
        mvc.perform(get("/throw/overdraft"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/insufficient-funds"));
    }

    @Test
    @DisplayName("a currency mismatch is 422 currency-mismatch")
    void currencyMismatchIsMapped() throws Exception {
        mvc.perform(get("/throw/currency"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/currency-mismatch"));
    }

    @Test
    @DisplayName("a request that is well formed but not actionable is 422")
    void notActionableIsMapped() throws Exception {
        mvc.perform(get("/throw/not-actionable"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://problems.tesserabank.example/not-actionable"));
    }

    @Test
    @DisplayName("a reused idempotency key is 409, and the key is not echoed back")
    void idempotencyConflictIsMapped() throws Exception {
        mvc.perform(get("/throw/idempotency"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/idempotency-conflict"))
                .andExpect(jsonPath("$.detail", not(containsString("secret-key-material"))));
    }

    @Test
    @DisplayName("an account opened twice is 409")
    void alreadyOpenIsMapped() throws Exception {
        mvc.perform(get("/throw/already-open"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/account-already-open"));
    }

    @Test
    @DisplayName("a terminal-state transition is 409 conflicting-state")
    void conflictingStateIsMapped() throws Exception {
        mvc.perform(get("/throw/already-reversed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/conflicting-state"));
        mvc.perform(get("/throw/illegal-state"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/conflicting-state"));
    }

    @Test
    @DisplayName("a missing required header is a Problem document, not Spring's own error body")
    void aMissingHeaderIsAProblem() throws Exception {
        mvc.perform(post("/throw/needs-header"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type")
                        .value("https://problems.tesserabank.example/validation-failed"));
    }

    @Test
    @DisplayName("an unrecognised failure leaks no class name, no SQL and no stack frame")
    void anUnexpectedFailureLeaksNothing() throws Exception {
        // REQ-API-003, asserted rather than assumed. Without the catch-all handler this response
        // carries the exception class and, for a SQLException, a fragment of the statement with it.
        mvc.perform(get("/throw/sql"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://problems.tesserabank.example/internal"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed."))
                .andExpect(content().string(not(containsString("SQLException"))))
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("idempotency_record"))))
                .andExpect(content().string(not(containsString("bank.tessera"))));
    }

    @Test
    @DisplayName("a correlation id is echoed when the gateway sent one, and absent otherwise")
    void theCorrelationIdIsEchoed() throws Exception {
        mvc.perform(get("/throw/not-found").header("X-Correlation-Id", "5c2f0b1e-0000-4000-8000-000000000001"))
                .andExpect(jsonPath("$.correlationId").value("5c2f0b1e-0000-4000-8000-000000000001"));
        mvc.perform(get("/throw/not-found")).andExpect(jsonPath("$.correlationId").doesNotExist());
    }

    /** Exists only to raise each failure the handler must map. */
    @RestController
    static class ThrowingController {

        @GetMapping("/throw/not-found")
        String notFound() {
            throw new AccountNotFoundException(AccountRef.of("TB00000000009999"));
        }

        @GetMapping("/throw/overdraft")
        String overdraft() {
            throw new OverdraftNotPermittedException(Money.of(-100, PLN), OverdraftPolicy.forbidden());
        }

        @GetMapping("/throw/currency")
        String currency() {
            throw new CurrencyMismatchException(PLN, CurrencyCode.of("EUR"));
        }

        @GetMapping("/throw/not-actionable")
        String notActionable() {
            throw NotActionableException.amountNotPositive(Money.zero(PLN));
        }

        @GetMapping("/throw/idempotency")
        String idempotency() {
            throw new IdempotencyConflictException("secret-key-material-0001");
        }

        @GetMapping("/throw/already-open")
        String alreadyOpen() {
            throw new AccountAlreadyOpenException(AccountRef.of("TB00000000000001"));
        }

        @GetMapping("/throw/already-reversed")
        String alreadyReversed() {
            throw new AlreadyReversedException(
                    EntryRef.of("TB202608190000000001"), EntryRef.of("TB202608190000000002"));
        }

        @GetMapping("/throw/illegal-state")
        String illegalState() {
            throw new IllegalStateException("Hold HL202608190000000001 is CAPTURED and cannot be captured.");
        }

        @GetMapping("/throw/sql")
        String sql() {
            throw new RuntimeException(
                    "SELECT * FROM idempotency_record WHERE key = ?", new SQLException("relation missing"));
        }

        @PostMapping("/throw/needs-header")
        String needsHeader(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }
    }
}
