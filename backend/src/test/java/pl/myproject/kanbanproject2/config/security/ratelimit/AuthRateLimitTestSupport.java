package pl.myproject.kanbanproject2.config.security.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;

import java.time.Duration;

/** Shared fixtures for the rate-limit tests. */
final class AuthRateLimitTestSupport {

    private AuthRateLimitTestSupport() {
    }

    /**
     * A clock the tests move by hand, so a cooldown can be asserted without any test waiting one
     * out. It starts negative on purpose: {@code System.nanoTime} has an arbitrary origin, and a
     * limiter that reads an unset field as "no cooldown" only misbehaves on the half of the number
     * line a test starting at zero never visits.
     */
    static final class FakeClock implements Ticker {

        private long nanos = -5_000_000_000L;

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long read() {
            return nanos;
        }
    }

    /** Defaults with the bursts small enough to exhaust in a readable number of calls. */
    static AuthRateLimitProperties properties() {
        return properties(0);
    }

    static AuthRateLimitProperties properties(int trustedProxyCount) {
        return new AuthRateLimitProperties(
                true,
                trustedProxyCount,
                1000,
                4, 2, Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofMinutes(15),
                3, 2, Duration.ofSeconds(15), Duration.ofMinutes(15), Duration.ofHours(1));
    }
}
