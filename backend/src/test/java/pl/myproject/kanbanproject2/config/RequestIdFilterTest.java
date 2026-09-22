package pl.myproject.kanbanproject2.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("the edge's id reaches the MDC and the response")
    void carriesTheEdgeId() throws Exception {
        String fromTheEdge = "0123456789abcdef0123456789abcdef";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, fromTheEdge);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> seenByTheChain = new AtomicReference<>();
        filter.doFilter(request, response, recording(seenByTheChain));

        assertThat(seenByTheChain.get())
                .as("the whole point: the id nginx logged is the id every application line carries")
                .isEqualTo(fromTheEdge);
        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .as("the edge does not echo it, so this is the only place a person can read it off")
                .isEqualTo(fromTheEdge);
    }

    @Test
    @DisplayName("a request that arrives without one is given one")
    void inventsAnIdWhenThereIsNone() throws Exception {
        // The API container is reachable directly - 127.0.0.1:8081 locally, and the platform's own
        // probes in the deployment - so "nginx always sets it" is not something this can assume.
        AtomicReference<String> seenByTheChain = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, recording(seenByTheChain));

        assertThat(seenByTheChain.get())
                .as("32 hex characters, the same shape nginx's $request_id has, so a line does not "
                        + "say which half of the deployment minted it")
                .matches("[0-9a-f]{32}");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seenByTheChain.get());
    }

    @Test
    @DisplayName("an id that does not look like one is replaced rather than logged")
    void refusesAnUnacceptableInboundId() throws Exception {
        // Whatever arrives here ends up in every log line for the request, so an unbounded or
        // punctuated value is a log entry of the caller's choosing. nginx applies the same pattern;
        // this is not trusting it to have done so.
        for (String hostile : new String[]{
                "short",                             // under the floor
                "x".repeat(65),                      // over the ceiling
                "abcdefgh\ninjected=line",           // a newline, the classic
                "abcdefgh\"quoted\"",                // breaks a JSON field
                " 0123456789abcdef0123456789abcd"}) { // leading space: matches() must be total

            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER, hostile);
            AtomicReference<String> seenByTheChain = new AtomicReference<>();

            filter.doFilter(request, new MockHttpServletResponse(), recording(seenByTheChain));

            assertThat(seenByTheChain.get())
                    .as("%s was believed", hostile)
                    .isNotEqualTo(hostile)
                    .matches("[0-9a-f]{32}");
        }
    }

    @Test
    @DisplayName("an acceptable id is passed through whatever its shape")
    void acceptsAnyIdInsideTheBounds() throws Exception {
        // Trusting an inbound id at all is the deliberate half: it is what lets a load test or a
        // future client correlate its own request with both halves of this deployment.
        String theirs = "load-test_run-42";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, theirs);
        AtomicReference<String> seenByTheChain = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), recording(seenByTheChain));

        assertThat(seenByTheChain.get()).isEqualTo(theirs);
    }

    @Test
    @DisplayName("the MDC is cleared even when the request blows up")
    void clearsTheMdcOnTheWayOut() {
        // Servlet threads are pooled and the MDC is a thread local, so a leak here stamps the next
        // request on this thread with the previous one's id - which reads as true and is not.
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("boom"); }))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(RequestIdFilter.MDC_KEY))
                .as("the id outlived the request it belongs to")
                .isNull();
    }

    @Test
    @DisplayName("nothing is left in the MDC after an ordinary request either")
    void clearsTheMdcAfterASuccess() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    /** A chain that reports what the MDC held while it ran, which is the only moment that matters. */
    private static FilterChain recording(AtomicReference<String> seen) {
        return (request, response) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY));
    }
}
