package pl.myproject.kanbanproject2.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitFilter;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimitFilter;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimitProperties;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SecurityConfigurationRateLimitWiringTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withUserConfiguration(TestCollaborators.class);

    @Test
    @DisplayName("the limiter sits after CORS and ahead of authorization")
    void isWiredIntoTheChain() {
        contextRunner.run(context -> {
            List<Class<?>> filters = filterTypes(context.getBean(SecurityFilterChain.class));

            assertThat(filters).contains(AuthRateLimitFilter.class);
            assertThat(filters.indexOf(AuthRateLimitFilter.class))
                    .isGreaterThan(filters.indexOf(CorsFilter.class))
                    .isLessThan(filters.indexOf(AuthorizationFilter.class));
        });
    }

    @Test
    @DisplayName("the limiter can be switched off without touching anything else in the chain")
    void canBeDisabled() {
        contextRunner.withPropertyValues("security.rate-limit.enabled=false").run(context -> {
            List<Class<?>> filters = filterTypes(context.getBean(SecurityFilterChain.class));

            assertThat(filters).doesNotContain(AuthRateLimitFilter.class)
                    .contains(CorsFilter.class, JwtAuthenticationFilter.class);
        });
    }

    @Test
    @DisplayName("configuration the limiter cannot work with stops the context rather than the traffic")
    void failsFastOnUnusableConfiguration() {
        contextRunner.withPropertyValues("security.rate-limit.credential-attempts-per-ip=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasStackTraceContaining("credential-attempts-per-ip"));
    }

    @Test
    @DisplayName("the per-account API limit sits right after the JWT filter, which finds the account it keys on")
    void apiLimitIsWiredAfterAuthentication() {
        contextRunner.run(context -> {
            List<Class<?>> filters = filterTypes(context.getBean(SecurityFilterChain.class));

            assertThat(filters.indexOf(ApiRateLimitFilter.class))
                    .isEqualTo(filters.indexOf(JwtAuthenticationFilter.class) + 1)
                    .isGreaterThan(filters.indexOf(CorsFilter.class));
        });
    }

    @Test
    @DisplayName("the per-account API limit can be switched off on its own")
    void apiLimitCanBeDisabled() {
        contextRunner.withPropertyValues("security.api-rate-limit.enabled=false").run(context -> {
            List<Class<?>> filters = filterTypes(context.getBean(SecurityFilterChain.class));

            assertThat(filters).doesNotContain(ApiRateLimitFilter.class).contains(AuthRateLimitFilter.class);
        });
    }

    @Test
    @DisplayName("an API limit of zero stops the context rather than refusing every call")
    void apiLimitFailsFast() {
        contextRunner.withPropertyValues("security.api-rate-limit.per-second=0")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("security.api-rate-limit.per-second"));
    }

    private static List<Class<?>> filterTypes(SecurityFilterChain chain) {
        return chain.getFilters().stream().<Class<?>>map(Filter::getClass).toList();
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
