package pl.myproject.kanbanproject2.config.security.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Slf4j
final class RedisEscalationStore implements EscalationStore {

    private static final RedisScript<List> SCRIPT = loadScript();

    private final StringRedisTemplate redis;

    RedisEscalationStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    @SuppressWarnings("unchecked")
    public AuthRateLimitDecision attempt(String key, Limit limit, long now) {
        try {
            List<Long> result = redis.execute(SCRIPT, List.of(key),
                    String.valueOf(now),
                    String.valueOf(limit.freeAttempts()),
                    String.valueOf(limit.baseCooldownMillis()),
                    String.valueOf(limit.maxCooldownMillis()),
                    String.valueOf(limit.windowMillis()));

            if (result == null || result.size() != 2) {
                throw new IllegalStateException("auth-rate-limit.lua returned " + result);
            }
            return result.get(0) == 1L
                    ? AuthRateLimitDecision.allow()
                    : AuthRateLimitDecision.refuse(Duration.ofMillis(result.get(1)));
        } catch (DataAccessException | IllegalStateException e) {
            log.warn("Rate limiter could not use Redis, allowing the attempt: {}", e.getMessage());
            return AuthRateLimitDecision.allow();
        }
    }

    private static RedisScript<List> loadScript() {
        try {
            String script = StreamUtils.copyToString(
                    new ClassPathResource("redis/auth-rate-limit.lua").getInputStream(), StandardCharsets.UTF_8);
            return new DefaultRedisScript<>(script, List.class);
        } catch (IOException e) {
            throw new UncheckedIOException("redis/auth-rate-limit.lua is missing from the classpath", e);
        }
    }
}
