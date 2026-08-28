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
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Guards the path every user takes into the packaged application.
 *
 * <p>The jar serves the React bundle itself, so the browser's first requests after {@code /} are
 * for files Spring Security has to let through before anyone holds a token. Nothing else in the
 * suite covers that: Jest replaces {@code fetch}, so no frontend test resolves a real URL, and
 * Cypress runs against the Vite dev server, which serves its own assets and never consults this
 * filter chain. A pattern that silently stops matching therefore shows up first as a blank page
 * in production — which is what {@code /*.js} did, because {@code *} does not cross a {@code /}
 * and Vite emits to {@code /assets/}.
 */
class PublicBundlePathsTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(TestCollaborators.class);

    @Test
    @DisplayName("an anonymous browser can fetch everything it needs to boot the app")
    void servesTheBundleToAnonymousCallers() {
        // Hashed filenames stand in for whatever Vite emits; only the directory is fixed.
        assertPermitted(
                "/",
                "/index.html",
                "/assets/index-BMKiHw11.js",
                "/assets/index-DGz0kgfl.css",
                "/assets/browser-ponyfill-l-ovD2rm.js",
                "/icon.svg",
                "/kanban-logo.png");
    }

    @Test
    @DisplayName("i18next can fetch every locale before login")
    void servesTheLocalesToAnonymousCallers() {
        // The language detector picks one of these before the app has rendered anything.
        assertPermitted(
                "/locales/en/translation.json",
                "/locales/pl/translation.json",
                "/locales/ja/translation.json");
    }

    private void assertPermitted(String... uris) {
        forEach(uris, (uri, status) -> assertThat(status)
                .withFailMessage("%s should be public for an anonymous browser, got %d", uri, status)
                .isEqualTo(SC_OK));
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
