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
import org.springframework.security.web.header.writers.PermissionsPolicyHeaderWriter;
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
                new PermissionsPolicyHeaderWriter(SecurityHeaders.PERMISSIONS_POLICY),
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
    @DisplayName("the shell is served with a content security policy")
    void theShellCarriesThePolicy() throws Exception {
        HttpServletResponse response = headersFor("/board");

        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp).isNotNull();
        assertThat(csp)
                .contains("default-src 'self'")
                .contains("frame-ancestors 'none'")
                .contains("https://www.google.com")
                .contains("https://www.gstatic.com")
                .contains("https://fonts.googleapis.com")
                .contains("https://fonts.gstatic.com");
    }

    @Test
    @DisplayName("the bundle gets the policy too, which is where the scan found it missing")
    void theBundleCarriesItAsWell() throws Exception {
        assertThat(headersFor("/assets/index-abc123.js").getHeader("Content-Security-Policy"))
                .isEqualTo(SecurityHeaders.CONTENT_SECURITY_POLICY);
        assertThat(headersFor("/assets/index-abc123.js").getHeader("Cross-Origin-Resource-Policy"))
                .isEqualTo("same-origin");
    }

    @Test
    @DisplayName("the four headers the scan named as absent are present")
    void theScansFindingsAreAnswered() throws Exception {
        HttpServletResponse response = headersFor("/");

        assertThat(response.getHeader("Content-Security-Policy")).isNotBlank();
        assertThat(response.getHeader("Permissions-Policy")).isNotBlank();
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
    @DisplayName("permissions policy switches off every capability rather than listing an allowance")
    void everyCapabilityIsRefused() {
        assertThat(SecurityHeaders.PERMISSIONS_POLICY)
                .contains("camera=()")
                .contains("microphone=()")
                .contains("geolocation=()")
                .contains("payment=()");
        assertThat(SecurityHeaders.PERMISSIONS_POLICY).doesNotContain("self").doesNotContain("*");
    }

    @Test
    @DisplayName("HSTS is written to a request that is not secure, because none of them are")
    void strictTransportSecurityIsWrittenBehindTheIngress() throws Exception {
        assertThat(headersFor("/").getHeader("Strict-Transport-Security"))
                .isEqualTo("max-age=31536000 ; includeSubDomains");
    }

    @Test
    @DisplayName("the bundle gets HSTS too, on the same insecure request")
    void theBundleCarriesHstsAsWell() throws Exception {
        assertThat(headersFor("/assets/index-abc123.js").getHeader("Strict-Transport-Security"))
                .isNotNull();
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
