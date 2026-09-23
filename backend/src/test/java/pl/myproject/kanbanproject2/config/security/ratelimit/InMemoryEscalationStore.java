package pl.myproject.kanbanproject2.config.security.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryEscalationStore implements EscalationStore {
    private final Map<String, Escalation> escalations = new ConcurrentHashMap<>();

    @Override
    public AuthRateLimitDecision attempt(String key, Limit limit, long now) {
        return escalations.computeIfAbsent(key, ignored -> new Escalation()).attempt(limit, now);
    }

    private static final class Escalation {
        private long attempts;
        private long nextAllowedAt;
        private long lastSeenAt;
        private boolean seen;

        synchronized AuthRateLimitDecision attempt(Limit limit, long now) {
            if (!seen || now - lastSeenAt >= limit.windowMillis()) {
                attempts = 0;
                nextAllowedAt = now;
            }
            lastSeenAt = now;
            seen = true;

            if (now < nextAllowedAt) {
                return AuthRateLimitDecision.refuse(Duration.ofMillis(nextAllowedAt - now));
            }

            attempts++;
            nextAllowedAt = now + cooldownAfter(limit, attempts);
            return AuthRateLimitDecision.allow();
        }

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
