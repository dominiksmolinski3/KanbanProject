package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import pl.myproject.kanbanproject2.user.User;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which requests spend a token, and what a refusal looks like on the wire. The bucket itself is
 * {@link ApiRateLimiterIntegrationTest}'s, against real Redis.
 */
class ApiRateLimitFilterTest {

    private final ApiRateLimiter limiter = mock(ApiRateLimiter.class);
    private final ApiRateLimitFilter filter = new ApiRateLimitFilter(limiter, new ObjectMapper());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(int id) {
        var user = new User("someone", "someone@example.test", "hashed");
        user.setId(id);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private MockHttpServletResponse run(String path, FilterChain chain) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", path), response, chain);
        return response;
    }

    @Test
    @DisplayName("a signed-in API call spends a token and goes through")
    void allowed() throws Exception {
        signedInAs(7);
        when(limiter.attempt(7)).thenReturn(AuthRateLimitDecision.allow());
        var chain = new MockFilterChain();

        var response = run("/api/tasks", chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).as("the request reached the rest of the chain").isNotNull();
    }

    @Test
    @DisplayName("an empty bucket is a 429 with Retry-After and the usual error body, and stops the request")
    void refused() throws Exception {
        signedInAs(7);
        when(limiter.attempt(7)).thenReturn(AuthRateLimitDecision.refuse(Duration.ofMillis(50)));
        var chain = new MockFilterChain();

        var response = run("/api/tasks", chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("\"TOO_MANY_REQUESTS\"");
        assertThat(chain.getRequest()).as("the controller never ran").isNull();
    }

    @Test
    @DisplayName("a call with no account is not this filter's to count")
    void anonymousPassesThrough() throws Exception {
        var chain = new MockFilterChain();

        run("/api/tasks", chain);

        verify(limiter, never()).attempt(any());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("only /api is counted - the socket handshake and the actuator are not")
    void onlyTheApi() throws Exception {
        signedInAs(7);

        run("/ws/info", new MockFilterChain());
        run("/actuator/health/readiness", new MockFilterChain());
        run("/apiary", new MockFilterChain());

        verify(limiter, never()).attempt(any());
    }

    @Test
    @DisplayName("with Redis unreachable the call is allowed, since the limit fails open")
    @SuppressWarnings("unchecked")
    void failsOpen() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        var registry = new SimpleMeterRegistry();
        var real = new ApiRateLimiter(new ApiRateLimitProperties(true, 20, 100), redis, registry);

        assertThat(real.attempt(7).allowed()).isTrue();
        assertThat(registry.counter(ApiRateLimiter.REFUSED_COUNTER).count()).isZero();
    }
}
