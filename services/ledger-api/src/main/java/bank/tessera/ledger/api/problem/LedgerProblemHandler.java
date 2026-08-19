package bank.tessera.ledger.api.problem;

import bank.tessera.ledger.api.correlation.CorrelationId;
import bank.tessera.ledger.application.AccountAlreadyOpenException;
import bank.tessera.ledger.application.AccountNotFoundException;
import bank.tessera.ledger.application.AlreadyReversedException;
import bank.tessera.ledger.application.HoldNotFoundException;
import bank.tessera.ledger.application.NotActionableException;
import bank.tessera.ledger.application.TransferNotFoundException;
import bank.tessera.ledger.domain.CurrencyMismatchException;
import bank.tessera.ledger.domain.OverdraftNotPermittedException;
import bank.tessera.ledger.domain.UnbalancedEntryException;
import bank.tessera.ledger.port.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns every failure into an RFC 9457 Problem Details document.
 *
 * <p>REQ-API-003 is two requirements in one sentence: errors must be machine-readable, and they must
 * leak nothing. The first is served by the {@code type} URI, which is stable and enumerated. The
 * second is served by never putting an exception's own message into a response unless the message
 * was written to be read by a client - so the domain's messages, which name amounts and account
 * references, are used, and anything unrecognised is answered with a fixed sentence and logged
 * instead.
 *
 * <p><strong>The last handler is the important one.</strong> Without a catch-all, an unrecognised
 * exception reaches Spring's default handling and the client learns the class name of whatever
 * failed - and, if a SQL error made it that far, a fragment of the statement with it.
 *
 * <p><strong>Ordered ahead of Spring's own.</strong> {@code spring.mvc.problemdetails.enabled} adds
 * a built-in advice so that framework failures are Problem documents too, and it is wanted - but it
 * answers with {@code type: about:blank}, which is the RFC's way of saying "nothing machine-readable
 * here". Both advices sit at the default precedence and the winner between them is unspecified, so
 * this one is pinned in front: every response the contract declares carries a {@code type} a client
 * can branch on.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class LedgerProblemHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LedgerProblemHandler.class);

    @ExceptionHandler({AccountNotFoundException.class, TransferNotFoundException.class,
            HoldNotFoundException.class})
    ProblemDetail notFound(RuntimeException failure, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ProblemType.NOT_FOUND, failure.getMessage(), request);
    }

    @ExceptionHandler(AccountAlreadyOpenException.class)
    ProblemDetail alreadyOpen(AccountAlreadyOpenException failure, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, ProblemType.ACCOUNT_ALREADY_OPEN, failure.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException failure, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT, ProblemType.IDEMPOTENCY_CONFLICT, failure.getMessage(), request);
    }

    @ExceptionHandler({AlreadyReversedException.class, IllegalStateException.class})
    ProblemDetail conflictingState(RuntimeException failure, HttpServletRequest request) {
        // IllegalStateException is what the domain raises for a transition out of a terminal state -
        // a hold already captured, a hold already released. Those are conflicts, not server faults.
        return problem(
                HttpStatus.CONFLICT, ProblemType.CONFLICTING_STATE, failure.getMessage(), request);
    }

    @ExceptionHandler(OverdraftNotPermittedException.class)
    ProblemDetail insufficientFunds(OverdraftNotPermittedException failure, HttpServletRequest request) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ProblemType.INSUFFICIENT_FUNDS,
                failure.getMessage(),
                request);
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ProblemDetail currencyMismatch(CurrencyMismatchException failure, HttpServletRequest request) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ProblemType.CURRENCY_MISMATCH,
                failure.getMessage(),
                request);
    }

    @ExceptionHandler({NotActionableException.class, UnbalancedEntryException.class})
    ProblemDetail notActionable(RuntimeException failure, HttpServletRequest request) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY, ProblemType.NOT_ACTIONABLE, failure.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail beanValidation(MethodArgumentNotValidException failure, HttpServletRequest request) {
        List<Map<String, String>> violations = new ArrayList<>();
        failure.getBindingResult()
                .getFieldErrors()
                .forEach(error -> violations.add(
                        Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage()))));

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                ProblemType.VALIDATION_FAILED,
                "One or more fields are not valid.",
                request);
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException failure, HttpServletRequest request) {
        // Raised by @Validated on a header or path variable - an Idempotency-Key shorter than the
        // sixteen characters the contract requires, for instance. Without this handler it reaches
        // the catch-all and a client is told the ledger failed when in fact their request was
        // rejected, which is a materially different thing to be told.
        List<Map<String, String>> violations = new ArrayList<>();
        failure.getConstraintViolations()
                .forEach(violation -> violations.add(Map.of(
                        "field", String.valueOf(violation.getPropertyPath()),
                        "message", violation.getMessage())));

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                ProblemType.VALIDATION_FAILED,
                "One or more fields are not valid.",
                request);
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    ProblemDetail badRequest(Exception failure, HttpServletRequest request) {
        // A malformed reference, an absent Idempotency-Key, a body that is not JSON. The message is
        // safe here because these are raised on the request rather than on the ledger's state - but
        // a body that failed to parse is deliberately not echoed back, only described.
        String detail = failure instanceof HttpMessageNotReadableException
                ? "The request body could not be read as JSON."
                : failure.getMessage();
        return problem(HttpStatus.BAD_REQUEST, ProblemType.VALIDATION_FAILED, detail, request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception failure, HttpServletRequest request) {
        // Logged in full, reported as nothing. This is the handler that stops a SQLException, a
        // constraint name or a stack frame reaching a caller, and it is deliberately last.
        LOG.error("Unhandled failure serving {} {}", request.getMethod(), request.getRequestURI(), failure);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemType.INTERNAL,
                "The request could not be completed.",
                request);
    }

    private static ProblemDetail problem(
            HttpStatus status, ProblemType type, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type.type());
        problem.setTitle(type.title());
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));

        // What a support engineer traces the failure with. Supplied by the gateway (WP-12) when it
        // has one, because a correlation id invented per tier correlates nothing - and generated by
        // CorrelationIdFilter when it does not, because an untraceable request is worse.
        CorrelationId.current().ifPresent(id -> problem.setProperty("correlationId", id));
        return problem;
    }
}
