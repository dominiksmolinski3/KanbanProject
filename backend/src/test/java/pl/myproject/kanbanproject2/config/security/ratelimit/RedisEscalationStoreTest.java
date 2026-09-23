package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisEscalationStoreTest {
    private static final EscalationStore.Limit LIMIT = new EscalationStore.Limit(
            4, Duration.ofSeconds(15).toMillis(), Duration.ofMinutes(5).toMillis(), Duration.ofMinutes(15).toMillis());

    @Test
    @DisplayName("a [1, 0] result is an allow")
    void anAllowedResultIsRead() {
        StringRedisTemplate redis = templateReturning(List.of(1L, 0L));

        AuthRateLimitDecision decision = new RedisEscalationStore(redis).attempt("k", LIMIT, 1_000L);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("a [0, wait] result is a refusal reporting that wait")
    void aRefusedResultIsRead() {
        StringRedisTemplate redis = templateReturning(List.of(0L, 15_000L));

        AuthRateLimitDecision decision = new RedisEscalationStore(redis).attempt("k", LIMIT, 1_000L);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("Redis being unreachable fails the attempt open rather than locking every caller out")
    void anUnreachableRedisFailsOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stub(redis).thenThrow(new RedisConnectionFailureException("connection refused"));

        AuthRateLimitDecision decision = new RedisEscalationStore(redis).attempt("k", LIMIT, 1_000L);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("a script result that is not two elements also fails open, not with a 500")
    void aMalformedResultFailsOpen() {
        StringRedisTemplate redis = templateReturning(List.of(1L));

        AuthRateLimitDecision decision = new RedisEscalationStore(redis).attempt("k", LIMIT, 1_000L);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("a null result - a script that answered nothing - also fails open")
    void aNullResultFailsOpen() {
        StringRedisTemplate redis = templateReturning(null);

        AuthRateLimitDecision decision = new RedisEscalationStore(redis).attempt("k", LIMIT, 1_000L);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    @DisplayName("a transient Redis failure that is not a connection loss also fails open")
    void anyDataAccessExceptionFailsOpen() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stub(redis).thenThrow(new QueryTimeoutException("timed out"));

        AuthRateLimitDecision decision = new RedisEscalationStore(redis).attempt("k", LIMIT, 1_000L);

        assertThat(decision.allowed()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static StringRedisTemplate templateReturning(List<Long> result) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        stub(redis).thenReturn((List) result);
        return redis;
    }

    @SuppressWarnings("unchecked")
    private static org.mockito.stubbing.OngoingStubbing<List> stub(StringRedisTemplate redis) {
        return when(redis.<List>execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()));
    }
}
