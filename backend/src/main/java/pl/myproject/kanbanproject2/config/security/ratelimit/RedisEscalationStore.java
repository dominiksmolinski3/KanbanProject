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

/**
 * The production {@link EscalationStore}: one Redis key per (rule, dimension, key) triple, read,
 * scored and rewritten inside a single Lua script - {@code redis/auth-rate-limit.lua} - so every
 * replica of the API sees the same escalation regardless of which pod a caller's next attempt lands
 * on. The script is this migration's equivalent of the outbox's {@code FOR UPDATE SKIP LOCKED} and
 * the deadline sweep's claim: the atomic unit lives where the shared state lives, not in the calling
 * process. As with those two, no compiler checks the Lua string - {@code AuthRateLimitScriptTest} is
 * the text guard, and {@code RedisEscalationStoreIntegrationTest} is the behavioural one, run
 * against a real Redis rather than mocked, because a script that only ever runs against a fake is a
 * script nothing has actually executed.
 */
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
            // Fails open, whether Redis was unreachable or answered something this store cannot
            // read. The limiter is defence in depth, not the control that stops a stolen password
            // working - losing the escalation for the length of an outage is a smaller cost than
            // an outage that also locks every caller out of authentication.
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
