package pl.myproject.kanbanproject2.config.security.ratelimit;

import java.time.Duration;

/**
 * The outcome of taking a token from one bucket. When the token was refused, {@code retryAfter}
 * is how long until the next one refills, which is what the {@code Retry-After} header reports.
 */
public record AuthRateLimitDecision(boolean allowed, Duration retryAfter) {

    private static final AuthRateLimitDecision ALLOWED = new AuthRateLimitDecision(true, Duration.ZERO);

    static AuthRateLimitDecision allow() {
        return ALLOWED;
    }

    static AuthRateLimitDecision refuse(Duration retryAfter) {
        return new AuthRateLimitDecision(false, retryAfter);
    }

    /** Rounded up, and never below one, so a caller told to retry always waits a whole second. */
    public long retryAfterSeconds() {
        return Math.max(1L, (retryAfter.toNanos() + 999_999_999L) / 1_000_000_000L);
    }
}
