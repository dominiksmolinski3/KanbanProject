package pl.myproject.kanbanproject2.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One identifier per request, in the MDC, so the two containers' logs can be joined.
 *
 * <p>Since the tier split there are two log streams and nothing shared between them: a 502 recorded
 * at the edge could not be matched to the API line for the same request. That is not a hypothetical
 * class of bug here - it is the class the split has already produced twice, the SNI reset and the
 * wrong {@code Host} header, both of which were diagnosed by hand-building {@code curl} against the
 * real origin because the logs could not answer it.
 *
 * <p>The edge generates the id (nginx's {@code $request_id}, or an acceptable inbound one) and
 * passes it on {@value #HEADER}. This filter puts it in the MDC under {@value #MDC_KEY}, where
 * Spring Boot's structured logging writes it as a field of every line, and echoes it on the
 * response - which the edge deliberately does not do, because nginx's {@code add_header} does not
 * inherit into a location that sets one of its own.
 *
 * <p>Three things about it are deliberate:
 *
 * <ul>
 *   <li><b>An inbound header is checked before it is believed.</b> It reaches this from whatever
 *       sent the request and then appears in every log line for it, so an unbounded one is a log
 *       entry of the caller's choosing. nginx applies the same pattern; this repeats it rather
 *       than trusting the edge, because the API container is also reachable directly - on
 *       {@code 127.0.0.1:8081} in the local stack, and by the platform's own probes in the
 *       deployment.</li>
 *   <li><b>It runs before everything, including Spring Security.</b> A request refused with a 401
 *       is exactly the one somebody asks about, and the chain that refuses it is registered at
 *       order -100 - so anything later would leave the interesting lines unlabelled.</li>
 *   <li><b>The MDC entry is removed in a finally block.</b> Servlet threads are pooled and the MDC
 *       is a thread local: leaving it set would stamp the next request on that thread with the
 *       previous one's id, which is worse than no id at all because it reads as true.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** Kept in step with {@code proxy_set_header} in the edge template by {@code RequestIdMatchesTheEdgeTest}. */
    public static final String HEADER = "X-Request-Id";

    /** The field name in every structured log line. */
    public static final String MDC_KEY = "requestId";

    /**
     * What an inbound id may look like. The same bound and alphabet the edge's {@code map} applies,
     * and the shape both generators produce: 32 hex characters.
     */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = acceptable(request.getHeader(HEADER)) ? request.getHeader(HEADER) : generate();

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static boolean acceptable(String candidate) {
        return candidate != null && ACCEPTABLE.matcher(candidate).matches();
    }

    /** nginx's {@code $request_id} shape, so a line does not say which half of the deployment made it. */
    private static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
