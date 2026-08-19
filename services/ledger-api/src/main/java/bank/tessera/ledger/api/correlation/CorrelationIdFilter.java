package bank.tessera.ledger.api.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id on every request, before anything else runs.
 *
 * <p><strong>Ordered first deliberately.</strong> The idempotency filter can reject a request before
 * a controller is ever reached, and its Problem document has to carry the same id as the response
 * header. A filter that ran second would leave that one path uncorrelated - which is precisely the
 * path a support engineer is looking at when they need the id.
 *
 * <p>The header is set on the response before the chain runs, not after. Once the body has been
 * written the response is committed and a header set then is silently dropped, so the ordering here
 * is load-bearing rather than stylistic.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // In a pooled container this thread serves somebody else next. An id left behind would
            // be attached to their request, and every log line it produced would name the wrong one.
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
