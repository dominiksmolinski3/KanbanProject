package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitTestSupport.properties;

class AuthRateLimitFilterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AuthRateLimiter limiter;
    private AuthRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        // Test capacities: CREDENTIALS 4 per address / 2 per account, EMAIL 3 per address / 2 per account.
        limiter = spy(new AuthRateLimiter(properties(), new AuthRateLimitTestSupport.FakeClock()));
        filter = new AuthRateLimitFilter(limiter, new ClientIpResolver(properties()), OBJECT_MAPPER);
    }

    @Test
    @DisplayName("a request to an endpoint that is not limited is passed straight through")
    void ignoresUnlimitedPaths() throws Exception {
        MockHttpServletRequest request = get("/tasks");
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.invocations).isEqualTo(1);
        assertThat(chain.request).isSameAs(request);
        verify(limiter, never()).tryConsume(any(), any(), any());
    }

    @Test
    @DisplayName("a CORS preflight is not charged, so a browser cannot spend a user's budget on it")
    void ignoresPreflight() throws Exception {
        MockHttpServletRequest request = login("{\"email\":\"a@example.com\"}");
        request.setMethod("OPTIONS");
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.invocations).isEqualTo(1);
        verify(limiter, never()).tryConsume(any(), any(), any());
    }

    @Test
    @DisplayName("the body survives the filter, so the controller still binds it")
    void leavesTheBodyReadableDownstream() throws Exception {
        String body = "{\"email\":\"a@example.com\",\"password\":\"correct horse\"}";
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(login(body), new MockHttpServletResponse(), chain);

        assertThat(chain.body).isEqualTo(body);
    }

    @Test
    @DisplayName("the account limit fires before the address limit is anywhere near exhausted")
    void limitsPerAccount() throws Exception {
        String body = "{\"email\":\"a@example.com\"}";

        assertThat(statusOf(login(body))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(login(body))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(login(body))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("two accounts attacked from one address are charged separately")
    void accountsAreChargedSeparately() throws Exception {
        String first = "{\"email\":\"a@example.com\"}";
        String second = "{\"email\":\"b@example.com\"}";

        assertThat(statusOf(login(first))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(login(first))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(login(second))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(login(second))).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    @DisplayName("spraying one password across many accounts still runs into the address limit")
    void limitsPerAddressAcrossAccounts() throws Exception {
        for (int account = 0; account < 4; account++) {
            assertThat(statusOf(login("{\"email\":\"user" + account + "@example.com\"}")))
                    .as("account %d", account)
                    .isEqualTo(HttpStatus.OK.value());
        }

        assertThat(statusOf(login("{\"email\":\"user4@example.com\"}")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("changing the capitalisation or padding of an address does not buy a fresh bucket")
    void normalisesTheAccountKey() throws Exception {
        assertThat(statusOf(login("{\"email\":\"a@example.com\"}"))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(login("{\"email\":\"A@Example.COM\"}"))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(login("{\"email\":\"  a@example.com  \"}")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("resend is charged against the address in its query string")
    void readsTheAccountFromAQueryParameter() throws Exception {
        assertThat(statusOf(resend("a@example.com"))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(resend("A@Example.com"))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(resend("a@example.com"))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

        // Three requests, one account bucket: the query parameter was read, and case-folded with it.
        verify(limiter, times(3))
                .tryConsume(AuthRateLimitRule.EMAIL, AuthRateLimitDimension.ACCOUNT, "a@example.com");
    }

    @Test
    @DisplayName("signup and resend share the mail budget, because they cost the same email")
    void signupAndResendShareTheMailBudget() throws Exception {
        assertThat(statusOf(signup("{\"email\":\"a@example.com\"}"))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(resend("a@example.com"))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(signup("{\"email\":\"a@example.com\"}")))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("a rejection says it is a rate limit, in the shape every other error uses")
    void rejectsWithTheStandardErrorBody() throws Exception {
        String body = "{\"email\":\"a@example.com\"}";
        statusOf(login(body));
        statusOf(login(body));

        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();
        filter.doFilter(login(body), response, chain);

        assertThat(chain.invocations).isZero();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(Long.parseLong(response.getHeader(HttpHeaders.RETRY_AFTER))).isPositive();

        JsonNode json = OBJECT_MAPPER.readTree(response.getContentAsString());
        assertThat(json.get("code").asText()).isEqualTo(ExceptionIdentifier.TOO_MANY_REQUESTS.name());
        assertThat(json.get("message").asText())
                .isEqualTo(ExceptionIdentifier.TOO_MANY_REQUESTS.getDefaultMessage());
    }

    @Test
    @DisplayName("a body the filter cannot parse still costs its address, and still reaches the controller")
    void fallsBackToTheAddressLimitOnAnUnparseableBody() throws Exception {
        String body = "not json at all";
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(login(body), new MockHttpServletResponse(), chain);

        assertThat(chain.invocations).isEqualTo(1);
        assertThat(chain.body).isEqualTo(body);
        verify(limiter).tryConsume(any(), eq(AuthRateLimitDimension.IP), any());
        verify(limiter, never()).tryConsume(any(), eq(AuthRateLimitDimension.ACCOUNT), any());
    }

    @Test
    @DisplayName("a form-encoded body is left alone rather than parsed as JSON")
    void ignoresANonJsonBody() throws Exception {
        MockHttpServletRequest request = login("email=a@example.com");
        request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.request).isSameAs(request);
        verify(limiter, never()).tryConsume(any(), eq(AuthRateLimitDimension.ACCOUNT), any());
    }

    @Test
    @DisplayName("a body larger than the buffer is replayed in full, address-limited but not account-limited")
    void streamsPastTheBufferWithoutLosingBytes() throws Exception {
        // The padding pushes the email past the prefix, so what the filter parses is truncated JSON.
        String padding = "x".repeat(AuthRateLimitFilter.MAX_BUFFERED_BODY_BYTES * 2);
        String body = "{\"padding\":\"" + padding + "\",\"email\":\"a@example.com\"}";
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(login(body), new MockHttpServletResponse(), chain);

        assertThat(chain.invocations).isEqualTo(1);
        assertThat(chain.body).isEqualTo(body);
        verify(limiter).tryConsume(any(), eq(AuthRateLimitDimension.IP), any());
        verify(limiter, never()).tryConsume(any(), eq(AuthRateLimitDimension.ACCOUNT), any());
    }

    @Test
    @DisplayName("a body larger than the buffer is readable one byte at a time as well")
    void replaysCorrectlyForSingleByteReads() throws Exception {
        String body = "{\"padding\":\"" + "x".repeat(AuthRateLimitFilter.MAX_BUFFERED_BODY_BYTES) + "\"}";
        StringBuilder seen = new StringBuilder();
        FilterChain chain = (request, response) -> {
            int next;
            while ((next = request.getInputStream().read()) >= 0) {
                seen.append((char) next);
            }
        };

        filter.doFilter(login(body), new MockHttpServletResponse(), chain);

        assertThat(seen.toString()).isEqualTo(body);
    }

    @Test
    @DisplayName("an empty body is charged to its address and nothing else")
    void handlesAnEmptyBody() throws Exception {
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(login(""), new MockHttpServletResponse(), chain);

        assertThat(chain.invocations).isEqualTo(1);
        verify(limiter, never()).tryConsume(any(), eq(AuthRateLimitDimension.ACCOUNT), any());
    }

    @Test
    @DisplayName("an email field that is not a string is ignored rather than keyed on")
    void ignoresANonTextualEmail() throws Exception {
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(login("{\"email\":{\"nested\":true}}"), new MockHttpServletResponse(), chain);

        assertThat(chain.invocations).isEqualTo(1);
        verify(limiter, never()).tryConsume(any(), eq(AuthRateLimitDimension.ACCOUNT), any());
    }

    private int statusOf(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new RecordingFilterChain());
        return response.getStatus();
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr("198.51.100.7");
        return request;
    }

    private static MockHttpServletRequest login(String body) {
        return jsonPost(AuthRateLimitRule.LOGIN_PATH, body);
    }

    private static MockHttpServletRequest signup(String body) {
        return jsonPost(AuthRateLimitRule.SIGNUP_PATH, body);
    }

    private static MockHttpServletRequest jsonPost(String uri, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr("198.51.100.7");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest resend(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", AuthRateLimitRule.RESEND_PATH);
        request.setRemoteAddr("198.51.100.7");
        request.setParameter("email", email);
        return request;
    }

    private static final class RecordingFilterChain implements FilterChain {

        private ServletRequest request;
        private String body;
        private int invocations;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
            this.request = request;
            this.invocations++;
            this.body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
