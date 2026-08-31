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
 * Holds one escalation per (rule, dimension, key) triple and hands out permission to proceed.
 *
 * <p>A key gets a burst of attempts for free. After that each attempt costs a cooldown, and the
 * cooldown doubles every time one is spent: fifteen seconds, thirty, a minute, two, up to a
 * ceiling. Being quiet for a whole window forgets the escalation and hands the burst back.
 *
 * <p>This replaced a token bucket, and the difference is what it does to the person who is not
 * attacking anything. A quota of five mails an hour is five mails and then a wall: the sixth
 * request was refused for the eleven minutes until a token trickled back, which is a long time to
 * stare at a form when the address you mistyped is sitting in the field in front of you. Doubling
 * spends an attacker's time just as effectively - the ceiling is a lower sustained rate than the
 * quota was - while the first wait a real person meets is fifteen seconds.
 *
 * <p>Escalation is charged on the way <em>out</em>: an attempt that is allowed sets the wait for
 * the next one. An attempt that is refused changes nothing at all, so hammering a key that is in
 * cooldown neither extends it nor escapes it - the answer is the same countdown it was already
 * serving.
 *
 * <p>Entries live in a {@link Caffeine} cache rather than a plain map for a reason: every distinct
 * address and every distinct email in a request creates one, both of them attacker-chosen, so an
 * unbounded map turns the limiter into a way to exhaust a 0.5Gi heap. The cache is bounded by
 * {@code maxTrackedKeys} and drops entries idle for longer than the widest window - which is
 * exactly when the escalation would have been forgiven anyway, making eviction a no-op rather than
 * a reprieve.
 */
@Component
public class AuthRateLimiter {

    private final Cache<String, Escalation> escalations;
    private final Map<AuthRateLimitRule, Map<AuthRateLimitDimension, Limit>> limits;
    private final Ticker ticker;

    /*
     * @Autowired is load-bearing, not decoration. There are two constructors here, so Spring stops
     * looking for the single obvious one and falls back to a no-arg constructor that does not
     * exist - the context then fails to start with "No default constructor found", and every bean
     * downstream of the security chain fails with it. Nothing caught that, because until
     * SpringContextStartsTest nothing in the suite ever built a context.
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
         * on to the ceiling. The wait is charged after the attempt that earns it, so a burst of
         * {@code freeAttempts} goes through untouched and the one after it is the first to wait.
         *
         * <p>Doubling is a shift, so the exponent is clamped before it can overflow into a negative
         * wait - at fifteen seconds that would take about thirty spent cooldowns, which is well
         * within what a determined attacker will sit through.
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
