package pl.myproject.kanbanproject2.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import pl.myproject.kanbanproject2.config.SpaRoutes;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * What the filter chain lets through, and — since the split — what it no longer has any reason to.
 * This class used to guard the opposite claim, when the jar served the bundle and fifteen static
 * patterns had to be permitted before anyone held a token. nginx serves all of it now from its own
 * container, so the assertions are inverted: the chain must refuse the bundle, refuse the client
 * routes, and still refuse the API behind them - deleting the guard outright would leave a merge
 * that puts a pattern back unnoticed.
 */
class PublicChainPathsTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(TestCollaborators.class);

    @Test
    @DisplayName("the chain serves none of the bundle, because it no longer has one")
    void permitsNothingStatic() {
        // Hashed filenames stand in for whatever Vite emits; only the directory ever mattered.
        assertDenied(
                "/index.html",
                "/assets/index-BMKiHw11.js",
                "/assets/index-DGz0kgfl.css",
                "/icon.svg",
                "/kanban-logo.png",
                "/locales/en/translation.json",
                "/locales/pl/translation.json");
    }

    @Test
    @DisplayName("the chain serves none of the client routes either")
    void permitsNoClientRoute() {
        // try_files answers these at the edge. Permitting them here would be permitting paths this
        // application has no handler for, which is how /*.json got next to a free-text label.
        assertDenied(SpaRoutes.ALL);
        assertDenied("/");
    }

    @Test
    @DisplayName("what a caller with no token still has to reach is still reachable")
    void stillPermitsWhatNeedsNoToken() {
        // The published contract and the probes: neither is behind a token, and narrowing the
        // chain must not have taken either with it. The auth routes are deliberately absent from
        // this list - AuthRateLimitFilter sits in front of them and a burst here would be flaky.
        assertPermitted("/v3/api-docs", "/actuator/health", "/actuator/health/readiness");
    }

    @Test
    @DisplayName("opening the bundle up did not open up the API behind it")
    void stillGuardsTheApi() {
        assertDenied(
                "/api/users",
                "/api/users/me",
                "/api/tasks",
                "/api/tasks/1/column-history",
                "/api/columns",
                "/api/rows",
                "/api/subtasks",
                "/api/files/1");
    }

    @Test
    @DisplayName("nothing is served on the unprefixed paths the API used to answer")
    void guardsTheUnprefixedPaths() {
        // /users was the one path that was both: a client route the chain permitted, and the name
        // UserController answered before the /api prefix existed. Neither is true here now, and a
        // matcher drifting back to any of them would otherwise be silent.
        assertDenied("/users", "/tasks", "/columns", "/rows", "/subtasks", "/files");
    }

    private void assertPermitted(String... uris) {
        forEach(uris, (uri, status) -> assertThat(status)
                .withFailMessage("%s should be public for an anonymous browser, got %d", uri, status)
                .isEqualTo(SC_OK));
    }

    private void assertDenied(String... uris) {
        forEach(uris, (uri, status) -> assertThat(status)
                .withFailMessage("%s should require authentication, got %d", uri, status)
                .isNotEqualTo(SC_OK));
    }

    private void forEach(String[] uris, StatusAssertion assertion) {
        contextRunner.run(context -> {
            FilterChainProxy chain = context.getBean(FilterChainProxy.class);
            for (String uri : uris) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
                request.setServletPath(uri);
                MockHttpServletResponse response = new MockHttpServletResponse();

                // MockFilterChain is the handler: reaching it leaves the response at its default
                // 200, so the status here is exactly "what security did with this request".
                chain.doFilter(request, response, new MockFilterChain());

                assertion.check(uri, response.getStatus());
            }
        });
    }

    @FunctionalInterface
    private interface StatusAssertion {
        void check(String uri, int status);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(SecurityConfiguration.class)
    static class TestCollaborators {

        @Bean
        JwtAuthenticationFilter jwtAuthenticationFilter() {
            return new JwtAuthenticationFilter(
                    mock(JwtService.class),
                    mock(UserDetailsService.class),
                    mock(HandlerExceptionResolver.class));
        }

        @Bean
        AuthenticationProvider authenticationProvider() {
            return mock(AuthenticationProvider.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AuthRateLimiter authRateLimiter(AuthRateLimitProperties properties) {
            return new AuthRateLimiter(properties);
        }

        @Bean
        ClientIpResolver clientIpResolver(AuthRateLimitProperties properties) {
            return new ClientIpResolver(properties);
        }
    }
}
