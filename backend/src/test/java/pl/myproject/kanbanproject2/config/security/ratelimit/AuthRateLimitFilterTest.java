package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        limiter = spy(new AuthRateLimiter(
                properties(), new InMemoryEscalationStore(), new AuthRateLimitTestSupport.FakeClock(),
                new SimpleMeterRegistry()));
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

    @ParameterizedTest
    @ValueSource(strings = {
            "not json at all",
            "",
            "[\"a@example.com\"]",
            "{\"email\":{\"nested\":true}}",
            "{\"email\":\"   \"}"})
    @DisplayName("a request that names an account but whose account cannot be read is refused, not let past the account limit")
    void refusesARequestWhoseAccountCannotBeRead(String body) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(login(body), response, chain);

        assertRefusedAsUnreadable(response, chain);
        verify(limiter).tryConsume(any(), eq(AuthRateLimitDimension.IP), any());
        verify(limiter, never()).tryConsume(any(), eq(AuthRateLimitDimension.ACCOUNT), any());
    }

    @Test
    @DisplayName("a form-encoded body to a route that names an account is refused rather than skipping the account limit")
    void refusesANonJsonBody() throws Exception {
        MockHttpServletRequest request = login("email=a@example.com");
        request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(request, response, chain);

        assertRefusedAsUnreadable(response, chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"leading-whitespace", "padding-field"})
    @DisplayName("padding the account past the buffer is refused: Spring would still read the address the filter cannot")
    void refusesAnAccountPaddedPastTheBuffer(String shape) throws Exception {
        String padding = " ".repeat(AuthRateLimitFilter.MAX_BUFFERED_BODY_BYTES);
        String body = shape.equals("leading-whitespace")
                ? padding + "{\"email\":\"a@example.com\"}"
                : "{\"email\":\"a@example.com\",\"pad\":\"" + padding + "\"}";
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(login(body), response, chain);

        assertRefusedAsUnreadable(response, chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/json;charset=UTF-8",
            "APPLICATION/JSON",
            "application/x+json",
            "application/merge-patch+json; charset=utf-8"})
    @DisplayName("every media type Spring binds as JSON is read for the account, so none of them buys a fresh bucket")
    void readsTheAccountUnderEveryJsonMediaType(String contentType) throws Exception {
        String body = "{\"email\":\"a@example.com\"}";

        for (int attempt = 0; attempt < 2; attempt++) {
            MockHttpServletRequest request = login(body);
            request.setContentType(contentType);
            assertThat(statusOf(request)).isEqualTo(HttpStatus.OK.value());
        }
        MockHttpServletRequest third = login(body);
        third.setContentType(contentType);

        assertThat(statusOf(third)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("a percent-encoded route is limited like the route Spring decodes it to")
    void limitsAnEncodedPathLikeTheDecodedOne() throws Exception {
        String body = "{\"email\":\"a@example.com\"}";

        assertThat(statusOf(login(body))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(jsonPost("/api/auth/%6Cogin", body))).isEqualTo(HttpStatus.OK.value());
        assertThat(statusOf(jsonPost("/api/auth/l%6Fgin", body))).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    @DisplayName("a route that names no account passes without one")
    void passesARefreshWithNoAccount() throws Exception {
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(refresh("{\"refreshToken\":\"abc\"}"), new MockHttpServletResponse(), chain);

        assertThat(chain.invocations).isEqualTo(1);
        verify(limiter).tryConsume(any(), eq(AuthRateLimitDimension.IP), any());
        verify(limiter, never()).tryConsume(any(), eq(AuthRateLimitDimension.ACCOUNT), any());
    }

    @Test
    @DisplayName("a body larger than the buffer is replayed in full")
    void streamsPastTheBufferWithoutLosingBytes() throws Exception {
        String padding = "x".repeat(AuthRateLimitFilter.MAX_BUFFERED_BODY_BYTES * 2);
        String body = "{\"padding\":\"" + padding + "\",\"refreshToken\":\"abc\"}";
        RecordingFilterChain chain = new RecordingFilterChain();

        filter.doFilter(refresh(body), new MockHttpServletResponse(), chain);

        assertThat(chain.invocations).isEqualTo(1);
        assertThat(chain.body).isEqualTo(body);
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

        filter.doFilter(refresh(body), new MockHttpServletResponse(), chain);

        assertThat(seen.toString()).isEqualTo(body);
    }

    private static void assertRefusedAsUnreadable(MockHttpServletResponse response, RecordingFilterChain chain)
            throws IOException {
        assertThat(chain.invocations).isZero();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(OBJECT_MAPPER.readTree(response.getContentAsString()).get("code").asText())
                .isEqualTo(AuthRateLimitFilter.UNREADABLE_CODE);
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

    private static MockHttpServletRequest refresh(String body) {
        return jsonPost(AuthRateLimitRule.REFRESH_PATH, body);
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
