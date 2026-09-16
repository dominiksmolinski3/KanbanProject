package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Answers whether one (rule, dimension, key) triple may take another attempt. A key gets a burst of
 * attempts free, then each attempt costs a doubling cooldown (15s, 30s, 60s, ... to a ceiling),
 * forgotten after a whole window of quiet - replacing a token bucket whose refusal fell hardest on
 * whoever had just made an honest mistake rather than on an attacker, who spends the ceiling's
 * sustained rate cheaply either way.
 *
 * <p>Escalation is charged on the way <em>out</em>: an allowed attempt sets the wait for the next
 * one, so hammering a key already in cooldown neither extends it nor escapes it.
 *
 * <p>The escalation itself lives in an {@link EscalationStore} - in production,
 * {@link RedisEscalationStore}, so every replica of the API reads and writes the same key rather
 * than each pod keeping its own count. This class only turns configuration into per-(rule,
 * dimension) {@link EscalationStore.Limit}s and hands the store a wall-clock reading; it holds no
 * state of its own.
 */
@Component
public class AuthRateLimiter {

    private final EscalationStore store;
    private final Map<AuthRateLimitRule, Map<AuthRateLimitDimension, EscalationStore.Limit>> limits;
    private final Clock clock;

    /*
     * @Autowired is load-bearing, not decoration: with two constructors here, Spring otherwise
     * looks for a no-arg one that does not exist and the context fails to start.
     */
    @Autowired
    public AuthRateLimiter(AuthRateLimitProperties properties, StringRedisTemplate redis) {
        this(properties, new RedisEscalationStore(redis), Clock.systemUTC());
    }

    /**
     * Visible for tests, which supply an in-memory store and a fixed clock so the escalation can be
     * asserted without a live Redis and without waiting a real cooldown out.
     */
    AuthRateLimiter(AuthRateLimitProperties properties, EscalationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
        this.limits = new EnumMap<>(AuthRateLimitRule.class);
        this.limits.put(AuthRateLimitRule.CREDENTIALS, dimensions(
                properties.credentialAttemptsPerIp(),
                properties.credentialAttemptsPerAccount(),
                properties.credentialBaseCooldown(),
                properties.credentialMaxCooldown(),
                properties.credentialWindow()));
        this.limits.put(AuthRateLimitRule.EMAIL, dimensions(
                properties.emailRequestsPerIp(),
                properties.emailRequestsPerAccount(),
                properties.emailBaseCooldown(),
                properties.emailMaxCooldown(),
                properties.emailWindow()));
    }

    /**
     * Takes one attempt against a key, and answers how long to wait when there is none to take.
     */
    public AuthRateLimitDecision tryConsume(AuthRateLimitRule rule, AuthRateLimitDimension dimension, String key) {
        EscalationStore.Limit limit = limits.get(rule).get(dimension);
        return store.attempt(cacheKey(rule, dimension, key), limit, clock.millis());
    }

    private static Map<AuthRateLimitDimension, EscalationStore.Limit> dimensions(
            long perIp, long perAccount, Duration base, Duration max, Duration window) {

        Map<AuthRateLimitDimension, EscalationStore.Limit> byDimension = new EnumMap<>(AuthRateLimitDimension.class);
        byDimension.put(AuthRateLimitDimension.IP,
                new EscalationStore.Limit(perIp, base.toMillis(), max.toMillis(), window.toMillis()));
        byDimension.put(AuthRateLimitDimension.ACCOUNT,
                new EscalationStore.Limit(perAccount, base.toMillis(), max.toMillis(), window.toMillis()));
        return byDimension;
    }

    private static String cacheKey(AuthRateLimitRule rule, AuthRateLimitDimension dimension, String key) {
        return "auth-rate-limit:" + rule.name() + '|' + dimension.name() + '|' + key;
    }
}
