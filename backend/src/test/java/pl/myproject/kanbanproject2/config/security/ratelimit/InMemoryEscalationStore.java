package pl.myproject.kanbanproject2.config.security.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A test-only {@link EscalationStore}, kept beside the tests that use it rather than in main so
 * nothing can wire the multi-replica bug this migration exists to fix back into production by
 * mistake. Otherwise the same algorithm {@code redis/auth-rate-limit.lua} runs atomically in Redis -
 * see {@code RedisEscalationStoreIntegrationTest}, which drives the real script against the same
 * scenarios this store's callers do, against a live Redis.
 */
final class InMemoryEscalationStore implements EscalationStore {

    private final Map<String, Escalation> escalations = new ConcurrentHashMap<>();

    @Override
    public AuthRateLimitDecision attempt(String key, Limit limit, long now) {
        return escalations.computeIfAbsent(key, ignored -> new Escalation()).attempt(limit, now);
    }

    /**
     * One key's position in the escalation. Every field is guarded by the instance lock; two
     * requests for the same key can land on two threads at once, and the whole point of the state is
     * that it counts each of them exactly once.
     */
    private static final class Escalation {

        private long attempts;
        private long nextAllowedAt;
        private long lastSeenAt;
        private boolean seen;

        synchronized AuthRateLimitDecision attempt(Limit limit, long now) {
            // A first sighting is initialised rather than compared against, and so is a key old
            // enough to have aged out of its own window - the two look identical from here.
            if (!seen || now - lastSeenAt >= limit.windowMillis()) {
                attempts = 0;
                nextAllowedAt = now;
            }
            // Refused attempts count as activity too, so hammering keeps a key from ageing out of
            // its escalation - the quiet that forgives one has to be actual quiet.
            lastSeenAt = now;
            seen = true;

            if (now < nextAllowedAt) {
                return AuthRateLimitDecision.refuse(Duration.ofMillis(nextAllowedAt - now));
            }

            attempts++;
            nextAllowedAt = now + cooldownAfter(limit, attempts);
            return AuthRateLimitDecision.allow();
        }

        /**
         * Free while the burst lasts, then {@code base}, {@code 2 x base}, {@code 4 x base} and so
         * on to the ceiling, charged after the attempt that earns it.
         */
        private static long cooldownAfter(Limit limit, long attempts) {
            long spent = attempts - limit.freeAttempts() + 1;
            if (spent < 1) {
                return 0;
            }
            long doublings = Math.min(spent - 1, 32);
            long cooldown = limit.baseCooldownMillis() << doublings;
            return Math.min(cooldown, limit.maxCooldownMillis());
        }
    }
}
