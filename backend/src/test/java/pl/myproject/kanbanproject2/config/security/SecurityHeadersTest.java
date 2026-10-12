package pl.myproject.kanbanproject2.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter;
import org.springframework.security.web.header.writers.HstsHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityHeadersTest {
    private MockHttpServletResponse headersFor(String path) throws Exception {
        CrossOriginOpenerPolicyHeaderWriter opener = new CrossOriginOpenerPolicyHeaderWriter();
        opener.setPolicy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN);
        CrossOriginResourcePolicyHeaderWriter resource = new CrossOriginResourcePolicyHeaderWriter();
        resource.setPolicy(CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy.SAME_ORIGIN);

        HeaderWriterFilter filter = new HeaderWriterFilter(List.of(
                new ContentSecurityPolicyHeaderWriter(SecurityHeaders.CONTENT_SECURITY_POLICY),
                new ReferrerPolicyHeaderWriter(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN),
                opener,
                resource,
                new HstsHeaderWriter(AnyRequestMatcher.INSTANCE,
                        SecurityHeaders.STRICT_TRANSPORT_SECURITY_MAX_AGE, true)));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        return response;
    }

    @Test
    @DisplayName("an API response carries a policy that allows nothing - it is JSON, never a page")
    void apiResponsesCarryTheLockedDownPolicy() throws Exception {
        assertThat(headersFor("/api/tasks").getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; frame-ancestors 'none'");
        assertThat(SecurityHeaders.CONTENT_SECURITY_POLICY)
                .as("a host here would mean the page's policy has crept back into the API; the "
                        + "page's is in frontend/nginx/security-headers.conf")
                .doesNotContain("https://");
    }

    @Test
    @DisplayName("an API response still carries the cross-origin isolation headers")
    void apiResponsesCarryTheCrossOriginHeaders() throws Exception {
        HttpServletResponse response = headersFor("/api/tasks");

        assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
        assertThat(response.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
    }

    @Test
    @DisplayName("Cross-Origin-Embedder-Policy is absent on purpose, and stays a decision")
    void embedderPolicyIsDeclinedRatherThanForgotten() throws Exception {
        assertThat(headersFor("/").getHeader("Cross-Origin-Embedder-Policy")).isNull();
    }

    @Test
    @DisplayName("the referrer policy does not leak a board URL to a third party")
    void referrerIsTrimmedCrossOrigin() throws Exception {
        assertThat(headersFor("/board").getHeader("Referrer-Policy"))
                .isEqualTo("strict-origin-when-cross-origin");
    }

    @Test
    @DisplayName("HSTS is written to a request that is not secure, because none of them are")
    void strictTransportSecurityIsWrittenBehindTheIngress() throws Exception {
        assertThat(headersFor("/").getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000 ; includeSubDomains");
    }

    @Test
    @DisplayName("a year, and not preloaded")
    void theMaxAgeIsAYearAndPreloadIsDeclined() throws Exception {
        assertThat(SecurityHeaders.STRICT_TRANSPORT_SECURITY_MAX_AGE).isEqualTo(31536000L);
        assertThat(headersFor("/").getHeader("Strict-Transport-Security")).doesNotContain("preload");
    }

    @Test
    @DisplayName("the chain is configured to write it, which the writer list above cannot show")
    void theChainOverridesTheDefaultMatcher() throws IOException {
        String configuration = Files.readString(
                Path.of("src/main/java/pl/myproject/kanbanproject2/config/security/SecurityConfiguration.java"),
                StandardCharsets.UTF_8);

        int hsts = configuration.indexOf("httpStrictTransportSecurity");
        assertThat(hsts)
                .as("SecurityConfiguration no longer configures HSTS at all, so the framework "
                        + "default is back - and the framework default fires on nothing here")
                .isNotNegative();

        assertThat(configuration.indexOf("AnyRequestMatcher.INSTANCE", hsts))
                .as("SecurityConfiguration no longer overrides the secure-request matcher, so HSTS "
                        + "is gated on request.isSecure() again - false on every request served "
                        + "behind an ingress that terminates TLS")
                .isNotNegative();
    }
}
