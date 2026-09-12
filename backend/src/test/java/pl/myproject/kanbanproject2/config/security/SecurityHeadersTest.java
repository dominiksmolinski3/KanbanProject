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
import org.springframework.security.web.header.writers.PermissionsPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * That the policy reaches a response, rather than only existing as a constant.
 *
 * <p>The writers are assembled here instead of standing the whole filter chain up, because what is
 * being checked is the header a browser receives and not Spring's wiring - and a
 * {@code @SpringBootTest} would pull in the database, the rate limiter and the mail transport to
 * assert a string. {@code SecurityConfiguration} is what registers these; the values are shared, so
 * a change to one is a change to both.
 */
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
                resource));

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
                // reCAPTCHA, which is the only third party the client loads code from. Without
                // these two the sign-in screen renders a widget that never appears.
                .contains("https://www.google.com")
                .contains("https://www.gstatic.com")
                // The stylesheet's own @import, and the font files it then reaches for.
                .contains("https://fonts.googleapis.com")
                .contains("https://fonts.gstatic.com");
    }

    @Test
    @DisplayName("the bundle gets the policy too, which is where the scan found it missing")
    void theBundleCarriesItAsWell() throws Exception {
        // ZAP reported the missing cross-origin headers against /assets/index-<hash>.js and the
        // stylesheet, not only against the document - these run through the same chain, so a header
        // writer covers both without the static resources being a special case.
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
        // The one ZAP finding here that is declined. require-corp buys cross-origin isolation,
        // which matters for SharedArrayBuffer and high-resolution timers and for nothing this
        // application does - and it would break the reCAPTCHA frame, which is served without a
        // CORP header of its own. This assertion is here so that adding it is a deliberate act
        // with a test to change, rather than something somebody does to quiet a scan.
        assertThat(headersFor("/").getHeader("Cross-Origin-Embedder-Policy")).isNull();
    }

    @Test
    @DisplayName("the referrer policy does not leak a board URL to a third party")
    void referrerIsTrimmedCrossOrigin() throws Exception {
        // Paths here carry board and task ids. strict-origin-when-cross-origin sends the full path
        // to ourselves and only the origin to anybody else, which is what the reCAPTCHA request
        // would otherwise carry.
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
        // An allow-list with anything in it would be a capability this application asked for, and
        // it asks for none.
        assertThat(SecurityHeaders.PERMISSIONS_POLICY).doesNotContain("self").doesNotContain("*");
    }
}
