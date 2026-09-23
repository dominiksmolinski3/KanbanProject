package pl.myproject.kanbanproject2.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import pl.myproject.kanbanproject2.config.SpaRoutes;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PublicChainPathsTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(TestCollaborators.class);

    @Test
    @DisplayName("the chain serves none of the bundle, because it no longer has one")
    void permitsNothingStatic() {
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
        assertDenied(SpaRoutes.ALL);
        assertDenied("/");
    }

    @Test
    @DisplayName("what a caller with no token still has to reach is still reachable")
    void stillPermitsWhatNeedsNoToken() {
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
                "/api/users/1/avatar");
    }

    @Test
    @DisplayName("metrics is exposed but not public - it follows health's detail rule, not its route rule")
    void metricsRequiresAuthentication() {
        assertDenied("/actuator/metrics", "/actuator/metrics/kanban.mail.outbox.dead_letters");
    }

    @Test
    @DisplayName("nothing is served on the unprefixed paths the API used to answer")
    void guardsTheUnprefixedPaths() {
        assertDenied("/users", "/tasks", "/columns", "/rows", "/subtasks");
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
            return new AuthRateLimiter(properties, mock(StringRedisTemplate.class), new SimpleMeterRegistry());
        }

        @Bean
        ApiRateLimiter apiRateLimiter(ApiRateLimitProperties properties) {
            return new ApiRateLimiter(properties, mock(StringRedisTemplate.class), new SimpleMeterRegistry());
        }

        @Bean
        ClientIpResolver clientIpResolver(AuthRateLimitProperties properties) {
            return new ClientIpResolver(properties);
        }
    }
}
