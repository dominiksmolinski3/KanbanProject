package pl.myproject.kanbanproject2.config.security.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

@Component
public class AuthRateLimiter {

    static final String REFUSED_COUNTER = "kanban.auth.ratelimit.refused";

    private final EscalationStore store;
    private final Map<AuthRateLimitRule, Map<AuthRateLimitDimension, EscalationStore.Limit>> limits;
    private final Clock clock;
    private final MeterRegistry registry;

    // Required: with two constructors Spring otherwise looks for a no-arg one and the context fails to start.
    @Autowired
    public AuthRateLimiter(AuthRateLimitProperties properties, StringRedisTemplate redis, MeterRegistry registry) {
        this(properties, new RedisEscalationStore(redis), Clock.systemUTC(), registry);
    }

    AuthRateLimiter(AuthRateLimitProperties properties, EscalationStore store, Clock clock, MeterRegistry registry) {
        this.store = store;
        this.clock = clock;
        this.registry = registry;
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

    public AuthRateLimitDecision tryConsume(AuthRateLimitRule rule, AuthRateLimitDimension dimension, String key) {
        EscalationStore.Limit limit = limits.get(rule).get(dimension);
        AuthRateLimitDecision decision = store.attempt(cacheKey(rule, dimension, key), limit, clock.millis());
        if (!decision.allowed()) {
            registry.counter(REFUSED_COUNTER, "rule", rule.name(), "dimension", dimension.name()).increment();
        }
        return decision;
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
