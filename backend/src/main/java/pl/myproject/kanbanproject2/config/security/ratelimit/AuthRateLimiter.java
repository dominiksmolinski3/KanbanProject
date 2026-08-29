package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Holds one token bucket per (rule, dimension, key) triple and hands out permission to proceed.
 *
 * <p>Buckets live in a {@link Caffeine} cache rather than a plain map for a reason: every distinct
 * address and every distinct email in a request creates an entry, both of them attacker-chosen, so
 * an unbounded map turns the limiter into a way to exhaust a 0.5Gi heap. The cache is bounded by
 * {@code maxTrackedKeys} and drops entries that have gone idle for longer than the widest window —
 * by which point the bucket has refilled to capacity anyway, making eviction a no-op rather than a
 * reprieve.
 */
@Component
public class AuthRateLimiter {

    private final Cache<String, Bucket> buckets;
    private final Map<AuthRateLimitRule, Map<AuthRateLimitDimension, Bandwidth>> bandwidths;
    private final TimeMeter timeMeter;

    /*
     * @Autowired is load-bearing, not decoration. There are two constructors here, so Spring stops
     * looking for the single obvious one and falls back to a no-arg constructor that does not
     * exist - the context then fails to start with "No default constructor found", and every bean
     * downstream of the security chain fails with it. Nothing caught that, because until
     * SpringContextStartsTest nothing in the suite ever built a context.
     */
    @Autowired
    public AuthRateLimiter(AuthRateLimitProperties properties) {
        this(properties, TimeMeter.SYSTEM_NANOTIME);
    }

    /** Visible for tests, which drive a fake clock so refill can be asserted without waiting. */
    AuthRateLimiter(AuthRateLimitProperties properties, TimeMeter timeMeter) {
        this.timeMeter = timeMeter;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.maxTrackedKeys())
                .expireAfterAccess(properties.longestWindow())
                .ticker(timeMeter::currentTimeNanos)
                .build();
        this.bandwidths = new EnumMap<>(AuthRateLimitRule.class);
        this.bandwidths.put(AuthRateLimitRule.CREDENTIALS, dimensions(
                properties.credentialAttemptsPerIp(),
                properties.credentialAttemptsPerAccount(),
                properties.credentialWindow()));
        this.bandwidths.put(AuthRateLimitRule.EMAIL, dimensions(
                properties.emailRequestsPerIp(),
                properties.emailRequestsPerAccount(),
                properties.emailWindow()));
    }

    /**
     * Takes one token. A refused call still leaves the bucket untouched beyond the failed attempt,
     * so hammering a limited key does not push its recovery further out.
     */
    public AuthRateLimitDecision tryConsume(AuthRateLimitRule rule, AuthRateLimitDimension dimension, String key) {
        Bandwidth bandwidth = bandwidths.get(rule).get(dimension);
        Bucket bucket = buckets.get(cacheKey(rule, dimension, key), ignored -> newBucket(bandwidth));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        return probe.isConsumed()
                ? AuthRateLimitDecision.allow()
                : AuthRateLimitDecision.refuse(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private Bucket newBucket(Bandwidth bandwidth) {
        return Bucket.builder()
                .addLimit(bandwidth)
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    private static Map<AuthRateLimitDimension, Bandwidth> dimensions(long perIp, long perAccount, Duration window) {
        Map<AuthRateLimitDimension, Bandwidth> byDimension = new EnumMap<>(AuthRateLimitDimension.class);
        byDimension.put(AuthRateLimitDimension.IP, bandwidth(perIp, window));
        byDimension.put(AuthRateLimitDimension.ACCOUNT, bandwidth(perAccount, window));
        return byDimension;
    }

    /**
     * Greedy refill: tokens trickle back across the window instead of all landing at the end of it,
     * so a user who runs out waits seconds for one more try rather than the whole window.
     */
    private static Bandwidth bandwidth(long capacity, Duration window) {
        return Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, window)
                .build();
    }

    private static String cacheKey(AuthRateLimitRule rule, AuthRateLimitDimension dimension, String key) {
        return rule.name() + '|' + dimension.name() + '|' + key;
    }
}
