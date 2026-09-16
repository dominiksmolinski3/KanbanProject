package pl.myproject.kanbanproject2.config.security.ratelimit;

/**
 * Where one (rule, dimension, key) triple's escalation lives. {@link RedisEscalationStore} is the
 * only production implementation - a test-only in-memory one lives beside the tests that use it, so
 * nothing in main can wire the multi-replica bug this migration exists to fix back in by accident.
 */
interface EscalationStore {

    /** What one (rule, dimension) pair costs: the free burst, and the shape of the wait after it. */
    record Limit(long freeAttempts, long baseCooldownMillis, long maxCooldownMillis, long windowMillis) {
    }

    /**
     * Takes one attempt against {@code key} as of {@code now} (epoch milliseconds - the caller's
     * clock, not the store's, so every replica scores the same key against the same reading and a
     * test can drive it without waiting a cooldown out).
     */
    AuthRateLimitDecision attempt(String key, Limit limit, long now);
}
