package pl.myproject.kanbanproject2.config.security.ratelimit;

interface EscalationStore {
    record Limit(long freeAttempts, long baseCooldownMillis, long maxCooldownMillis, long windowMillis) {
    }

    AuthRateLimitDecision attempt(String key, Limit limit, long now);
}
