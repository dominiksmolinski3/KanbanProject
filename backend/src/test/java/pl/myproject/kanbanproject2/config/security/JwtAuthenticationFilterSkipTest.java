package pl.myproject.kanbanproject2.config.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.servlet.HandlerExceptionResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The untested half of the public-path problem {@link PublicBundlePathsTest} covers on the
 * authorize side. This filter kept its own copy of the list, and both halves of that copy were
 * wrong: {@code /auth/} moved under {@code /api} when the prefix landed, and the extension regex
 * matched the end of any path, not just static assets.
 */
class JwtAuthenticationFilterSkipTest {

    private JwtService jwtService;
    private UserDetailsService userDetailsService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userDetailsService = mock(UserDetailsService.class);
        filter = new JwtAuthenticationFilter(
                jwtService, userDetailsService, mock(HandlerExceptionResolver.class));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // The bug this fixes: a label is free text, so any of the fourteen extensions in the
            // old regex made a real API route unusable.
            "/api/tasks/5/label/build.js",
            "/api/tasks/5/label/notes.json",
            "/api/tasks/5/label/design.svg",
            "/api/users/1/avatar",
            "/api/tasks",
            "/api/columns"
    })
    @DisplayName("API routes are authenticated, whatever the last path segment looks like")
    void authenticatesApiRoutes(String path) throws Exception {
        var chain = authenticatedRequestTo(path);

        verify(jwtService).extractUsername("token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        verify(chain).doFilter(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/login",
            "/api/auth/signup",
            "/api/auth/verify",
            "/api/auth/resend",
            "/actuator/health",
            "/actuator/health/readiness",
            "/actuator/info",
            "/ws/info",
            "/error",
            "/assets/index-BMKiHw11.js",
            "/locales/pl/translation.json",
            "/index.html",
            "/kanban-logo.png"
    })
    @DisplayName("public paths skip the filter entirely")
    void skipsPublicPaths(String path) throws Exception {
        var chain = authenticatedRequestTo(path);

        verifyNoInteractions(jwtService, userDetailsService);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("the pre-prefix auth paths are no longer skipped")
    void unprefixedAuthPathsAreNoLongerPublic() throws Exception {
        authenticatedRequestTo("/auth/login");

        verify(jwtService).extractUsername("token");
    }

    @Test
    @DisplayName("a preflight is skipped on any path")
    void skipsPreflight() throws Exception {
        var request = new MockHttpServletRequest("OPTIONS", "/api/tasks");
        var chain = mock(FilterChain.class);

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        verifyNoInteractions(jwtService);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("a request with no bearer token passes through unauthenticated")
    void anonymousRequestIsNotAuthenticated() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/tasks");
        var chain = mock(FilterChain.class);

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwtService, never()).extractUsername(anyString());
        verify(chain).doFilter(any(), any());
    }

    /** Issues a GET to {@code path} carrying a bearer token that resolves to a valid user. */
    private FilterChain authenticatedRequestTo(String path) throws Exception {
        var principal = mock(UserDetails.class);
        when(jwtService.extractUsername("token")).thenReturn("user@example.com");
        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(principal);
        when(jwtService.isTokenValid("token", principal)).thenReturn(true);

        var request = new MockHttpServletRequest("GET", path);
        request.addHeader("Authorization", "Bearer token");
        var chain = mock(FilterChain.class);

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
