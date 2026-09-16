package pl.myproject.kanbanproject2.config.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Shared fixtures for the rate-limit tests. */
final class AuthRateLimitTestSupport {

    private AuthRateLimitTestSupport() {
    }

    /** A clock the tests move by hand, so a cooldown can be asserted without waiting one out. */
    static final class FakeClock extends Clock {

        private long millis = 5_000_000L;

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("tests only ever read this clock in UTC");
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
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
                3, 2, Duration.ofSeconds(15), Duration.ofMinutes(15), Duration.ofHours(1),
                "localhost", 6379, "", false);
    }
}
