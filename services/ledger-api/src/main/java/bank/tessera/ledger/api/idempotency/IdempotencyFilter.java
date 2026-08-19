package bank.tessera.ledger.api.idempotency;

import bank.tessera.ledger.api.correlation.CorrelationId;
import bank.tessera.ledger.api.problem.ProblemType;
import bank.tessera.ledger.api.problem.ProblemWriter;
import bank.tessera.ledger.port.IdempotencyConflictException;
import bank.tessera.ledger.port.IdempotencyStore;
import bank.tessera.ledger.port.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes every money-moving request idempotent.
 *
 * <p>The shape is the important part. Claiming the key, doing the work and storing the response all
 * happen inside <strong>one transaction</strong>, and every use case below joins it. That is what
 * makes the guarantee real rather than probable: a retry arriving while the first request is still
 * in flight blocks on the claim until the first commits, and then replays its answer instead of
 * moving money a second time. Claim and work in separate transactions and there is a window in which
 * both requests believe they are the first.
 *
 * <p><strong>Only a successful response is recorded.</strong> A rejected transfer must stay
 * retryable - the client may fix the amount and try again with the same key, and a stored {@code 422}
 * would answer that forever. So a non-2xx rolls the transaction back, which releases the claim as
 * well as undoing anything the request had written.
 *
 * <p><strong>A replay answers {@code 200}, whatever the original returned.</strong> The contract
 * declares that status on every money-moving operation for exactly this: {@code 201 Created} is a
 * statement that something was created now, and repeating it for a request that created nothing this
 * time would be false. The body is the original's, byte for byte.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    static final String HEADER = "Idempotency-Key";
    private static final int MINIMUM_KEY_LENGTH = 16;
    private static final int MAXIMUM_KEY_LENGTH = 64;

    /** Exactly the five operations the OpenAPI document marks as requiring the header. */
    private static final List<String> MONEY_MOVING = List.of(
            "/v1/transfers",
            "/v1/transfers/*/reversals",
            "/v1/accounts/*/holds",
            "/v1/holds/*/capture",
            "/v1/holds/*/release");

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final IdempotencyStore store;
    private final RequestFingerprint fingerprints;
    private final TransactionTemplate transactions;
    private final ProblemWriter problems;

    public IdempotencyFilter(
            IdempotencyStore store,
            RequestFingerprint fingerprints,
            TransactionTemplate transactions,
            ProblemWriter problems) {
        this.store = store;
        this.fingerprints = fingerprints;
        this.transactions = transactions;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!isPost(request)) {
            return true;
        }
        String path = request.getRequestURI();
        boolean moneyMoving = MONEY_MOVING.stream().anyMatch(pattern -> PATHS.match(pattern, path));
        if (!moneyMoving) {
            return true;
        }
        // An absent or badly sized key is not this filter's failure to report. The controller
        // declares the header, so Spring's binding raises it and the Problem handler answers with a
        // 400 that names the field - which is a better error than anything a filter could write.
        String key = request.getHeader(HEADER);
        return key == null || key.length() < MINIMUM_KEY_LENGTH || key.length() > MAXIMUM_KEY_LENGTH;
    }

    private static boolean isPost(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        CachedBodyRequest cached = new CachedBodyRequest(request, request.getInputStream().readAllBytes());
        String key = request.getHeader(HEADER);
        String fingerprint =
                fingerprints.of(request.getMethod(), request.getRequestURI(), cached.body());

        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);

        StoredResponse replay;
        try {
            replay = claimAndServe(key, fingerprint, cached, captured, chain);
        } catch (IdempotencyConflictException conflict) {
            // Thrown inside the transaction, which puts it outside Spring MVC's reach: an exception
            // escaping a filter never sees @RestControllerAdvice. Reported here so that the same key
            // with a different body is the 409 the contract promises rather than a container error.
            problems.write(
                    response, request, HttpStatus.CONFLICT, ProblemType.IDEMPOTENCY_CONFLICT,
                    conflict.getMessage());
            return;
        }

        if (replay == null) {
            captured.copyBodyToResponse();
            return;
        }

        response.reset();
        CorrelationId.applyTo(response);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(replay.body());
        response.getWriter().flush();
    }

    private StoredResponse claimAndServe(
            String key,
            String fingerprint,
            CachedBodyRequest cached,
            ContentCachingResponseWrapper captured,
            FilterChain chain) {
        return transactions.execute(status -> {
            Optional<StoredResponse> previous = store.claim(key, fingerprint);
            if (previous.isPresent()) {
                return previous.get();
            }

            try {
                chain.doFilter(cached, captured);
            } catch (IOException | ServletException failure) {
                throw new IllegalStateException("Failed to serve an idempotent request.", failure);
            }

            if (!HttpStatus.valueOf(captured.getStatus()).is2xxSuccessful()) {
                // Nothing worth replaying happened, and anything the request wrote must go with it.
                status.setRollbackOnly();
                return null;
            }

            String body = new String(captured.getContentAsByteArray(), StandardCharsets.UTF_8);
            store.store(key, StoredResponse.of(captured.getStatus(), body));
            return null;
        });
    }
}
