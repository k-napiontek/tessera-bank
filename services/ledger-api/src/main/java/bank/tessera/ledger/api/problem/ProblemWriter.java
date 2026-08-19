package bank.tessera.ledger.api.problem;

import bank.tessera.ledger.api.correlation.CorrelationId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

/**
 * Writes a Problem document straight to the response.
 *
 * <p>Needed because a servlet filter sits <em>outside</em> Spring MVC: an exception thrown there
 * never reaches {@code @RestControllerAdvice}, and the container answers with its own error page
 * instead - which is both the wrong media type and, by default, a stack trace. The idempotency
 * filter has one failure of its own to report, so it reports it properly.
 *
 * <p>The same {@link ProblemDetail} type the advice returns is serialised here, so both paths emit
 * an identical document and the contract test cannot tell them apart.
 */
public final class ProblemWriter {

    private final ObjectMapper json;

    public ProblemWriter(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public void write(
            HttpServletResponse response,
            HttpServletRequest request,
            HttpStatus status,
            ProblemType type,
            String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(type.type());
        problem.setTitle(type.title());
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));

        // The resolved id, not the raw header: a request that arrived without one still has an id,
        // and a client that sent something that was not a UUID does not get it echoed back.
        CorrelationId.current().ifPresent(id -> problem.setProperty("correlationId", id));

        response.reset();
        CorrelationId.applyTo(response);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(problem));
        response.getWriter().flush();
    }
}
