package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Holds one escalation per (rule, dimension, key) triple and hands out permission to proceed. A key
 * gets a burst of attempts free, then each attempt costs a doubling cooldown (15s, 30s, 60s, ... to
 * a ceiling), forgotten after a whole window of quiet - replacing a token bucket whose refusal fell
 * hardest on whoever had just made an honest mistake rather than on an attacker, who spends the
 * ceiling's sustained rate cheaply either way.
 *
 * <p>Escalation is charged on the way <em>out</em>: an allowed attempt sets the wait for the next
 * one, so hammering a key already in cooldown neither extends it nor escapes it.
 *
 * <p>Entries live in a {@link Caffeine} cache, not a plain map, because every distinct
 * attacker-chosen address or email creates one; it is bounded by {@code maxTrackedKeys} and drops
 * entries idle past the widest window, exactly when the escalation would have been forgiven anyway.
 */
@Component
public class AuthRateLimiter {

    private final Cache<String, Escalation> escalations;
    private final Map<AuthRateLimitRule, Map<AuthRateLimitDimension, Limit>> limits;
    private final Ticker ticker;

    /*
     * @Autowired is load-bearing, not decoration: with two constructors here, Spring otherwise
     * looks for a no-arg one that does not exist and the context fails to start.
     */
    @Autowired
    public AuthRateLimiter(AuthRateLimitProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /** Visible for tests, which drive a fake clock so cooldowns can be asserted without waiting. */
    AuthRateLimiter(AuthRateLimitProperties properties, Ticker ticker) {
        this.ticker = ticker;
        this.escalations = Caffeine.newBuilder()
                .maximumSize(properties.maxTrackedKeys())
                .expireAfterAccess(properties.longestWindow())
                .ticker(ticker)
                .build();
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
        Limit limit = limits.get(rule).get(dimension);
        Escalation escalation = escalations.get(cacheKey(rule, dimension, key), ignored -> new Escalation());

        return escalation.attempt(limit, ticker.read());
    }

    /** What one (rule, dimension) pair costs: the free burst, and the shape of the wait after it. */
    private record Limit(long freeAttempts, long baseCooldownNanos, long maxCooldownNanos, long windowNanos) {
    }

    /**
     * One key's position in the escalation. Every field is guarded by the instance lock; two
     * requests for the same key can land on two Tomcat threads at once, and the whole point of the
     * state is that it counts each of them exactly once.
     */
    private static final class Escalation {

        private long attempts;
        private long nextAllowedAt;
        private long lastSeenAt;
        private boolean seen;

        synchronized AuthRateLimitDecision attempt(Limit limit, long now) {
            // A first sighting is initialised rather than compared against: the ticker is
            // System.nanoTime, whose origin is arbitrary and whose readings are routinely negative,
            // so a zero-valued nextAllowedAt is not "no cooldown" but an arbitrary point in time.
            if (!seen || now - lastSeenAt >= limit.windowNanos()) {
                attempts = 0;
                nextAllowedAt = now;
            }
            // Refused attempts count as activity too, so hammering keeps a key from ageing out of
            // its escalation - the quiet that forgives one has to be actual quiet.
            lastSeenAt = now;
            seen = true;

            if (now - nextAllowedAt < 0) {
                return AuthRateLimitDecision.refuse(Duration.ofNanos(nextAllowedAt - now));
            }

            attempts++;
            nextAllowedAt = now + cooldownAfter(limit, attempts);
            return AuthRateLimitDecision.allow();
        }

        /**
         * Free while the burst lasts, then {@code base}, {@code 2 x base}, {@code 4 x base} and so
         * on to the ceiling, charged after the attempt that earns it. Doubling is a shift, so the
         * exponent is clamped before it can overflow into a negative wait.
         */
        private static long cooldownAfter(Limit limit, long attempts) {
            long spent = attempts - limit.freeAttempts() + 1;
            if (spent < 1) {
                return 0;
            }
            long doublings = Math.min(spent - 1, 32);
            long cooldown = limit.baseCooldownNanos() << doublings;
            return Math.min(cooldown, limit.maxCooldownNanos());
        }
    }

    private static Map<AuthRateLimitDimension, Limit> dimensions(
            long perIp, long perAccount, Duration base, Duration max, Duration window) {

        Map<AuthRateLimitDimension, Limit> byDimension = new EnumMap<>(AuthRateLimitDimension.class);
        byDimension.put(AuthRateLimitDimension.IP,
                new Limit(perIp, base.toNanos(), max.toNanos(), window.toNanos()));
        byDimension.put(AuthRateLimitDimension.ACCOUNT,
                new Limit(perAccount, base.toNanos(), max.toNanos(), window.toNanos()));
        return byDimension;
    }

    private static String cacheKey(AuthRateLimitRule rule, AuthRateLimitDimension dimension, String key) {
        return rule.name() + '|' + dimension.name() + '|' + key;
    }
}
