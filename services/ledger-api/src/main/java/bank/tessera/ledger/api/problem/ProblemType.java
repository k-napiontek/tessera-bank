package bank.tessera.ledger.api.problem;

import java.net.URI;

/**
 * The stable, machine-readable identifiers a Problem Details document can carry.
 *
 * <p>RFC 9457 makes {@code type} the part a client is allowed to branch on, and {@code title} and
 * {@code detail} the parts that may be reworded freely. Keeping the URIs in one enum is what makes
 * that promise checkable: a reworded title changes one string here, and a changed {@code type} is
 * visibly a contract change rather than a typo in a controller.
 */
public enum ProblemType {
    VALIDATION_FAILED("validation-failed", "Request is not valid"),
    NOT_FOUND("not-found", "Not found"),
    ACCOUNT_ALREADY_OPEN("account-already-open", "Account already open"),
    IDEMPOTENCY_CONFLICT("idempotency-conflict", "Idempotency key reused with a different request"),
    CONFLICTING_STATE("conflicting-state", "Resource is not in a state that permits this"),
    INSUFFICIENT_FUNDS("insufficient-funds", "Insufficient funds"),
    CURRENCY_MISMATCH("currency-mismatch", "Currency mismatch"),
    NOT_ACTIONABLE("not-actionable", "Request cannot be carried out"),
    INTERNAL("internal", "Internal error");

    private static final String BASE = "https://problems.tesserabank.example/";

    private final URI type;
    private final String title;

    ProblemType(String slug, String title) {
        this.type = URI.create(BASE + slug);
        this.title = title;
    }

    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }
}
