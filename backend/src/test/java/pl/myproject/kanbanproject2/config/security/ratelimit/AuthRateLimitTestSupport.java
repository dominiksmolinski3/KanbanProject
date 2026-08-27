package pl.myproject.kanbanproject2.config.security.ratelimit;

import io.github.bucket4j.TimeMeter;

import java.time.Duration;

/** Shared fixtures for the rate-limit tests. */
final class AuthRateLimitTestSupport {

    private AuthRateLimitTestSupport() {
    }

    /**
     * A clock the tests move by hand, so refill can be asserted without any test waiting for a
     * real window to pass.
     */
    static final class FakeClock implements TimeMeter {

        private long nanos = 1_000_000_000L;

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long currentTimeNanos() {
            return nanos;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }

    /** Defaults with the two capacities small enough to exhaust in a readable number of calls. */
    static AuthRateLimitProperties properties() {
        return properties(0);
    }

    static AuthRateLimitProperties properties(int trustedProxyCount) {
        return new AuthRateLimitProperties(
                true,
                trustedProxyCount,
                1000,
                4, 2, Duration.ofMinutes(5),
                3, 2, Duration.ofHours(1));
    }
}
