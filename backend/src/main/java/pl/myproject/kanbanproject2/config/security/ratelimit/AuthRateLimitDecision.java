package pl.myproject.kanbanproject2.config.security.ratelimit;

import java.time.Duration;

public record AuthRateLimitDecision(boolean allowed, Duration retryAfter) {
    private static final AuthRateLimitDecision ALLOWED = new AuthRateLimitDecision(true, Duration.ZERO);

    static AuthRateLimitDecision allow() {
        return ALLOWED;
    }

    static AuthRateLimitDecision refuse(Duration retryAfter) {
        return new AuthRateLimitDecision(false, retryAfter);
    }

    public long retryAfterSeconds() {
        return Math.max(1L, (retryAfter.toNanos() + 999_999_999L) / 1_000_000_000L);
    }
}
