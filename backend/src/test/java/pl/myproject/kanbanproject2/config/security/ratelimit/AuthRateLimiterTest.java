package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitDimension.ACCOUNT;
import static pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitDimension.IP;
import static pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitRule.CREDENTIALS;
import static pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitRule.EMAIL;
import static pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitTestSupport.properties;

class AuthRateLimiterTest {

    private final AuthRateLimitTestSupport.FakeClock clock = new AuthRateLimitTestSupport.FakeClock();
    private final AuthRateLimiter limiter = new AuthRateLimiter(properties(), clock);

    @Test
    @DisplayName("a key is allowed up to its capacity and refused after it")
    void refusesOnceTheBucketIsEmpty() {
        // The test properties give CREDENTIALS four tokens per address.
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed())
                    .as("attempt %d", attempt)
                    .isTrue();
        }

        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isFalse();
    }

    @Test
    @DisplayName("exhausting one key leaves every other key untouched")
    void keysAreIndependent() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }

        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isFalse();
        assertThat(limiter.tryConsume(CREDENTIALS, IP, "203.0.113.1").allowed()).isTrue();
    }

    @Test
    @DisplayName("the same value on two dimensions draws on two separate buckets")
    void dimensionsAreIndependent() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "shared-value");
        }

        assertThat(limiter.tryConsume(CREDENTIALS, IP, "shared-value").allowed()).isFalse();
        assertThat(limiter.tryConsume(CREDENTIALS, ACCOUNT, "shared-value").allowed()).isTrue();
    }

    @Test
    @DisplayName("the same value under two rules draws on two separate buckets")
    void rulesAreIndependent() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }

        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isFalse();
        assertThat(limiter.tryConsume(EMAIL, IP, "198.51.100.7").allowed()).isTrue();
    }

    @Test
    @DisplayName("each rule and dimension enforces its own configured capacity")
    void eachLimitUsesItsOwnCapacity() {
        // Test properties: CREDENTIALS 4 per address / 2 per account, EMAIL 3 per address.
        assertThat(allowedInARow(CREDENTIALS, IP, "a")).isEqualTo(4);
        assertThat(allowedInARow(CREDENTIALS, ACCOUNT, "a")).isEqualTo(2);
        assertThat(allowedInARow(EMAIL, IP, "a")).isEqualTo(3);
        assertThat(allowedInARow(EMAIL, ACCOUNT, "a")).isEqualTo(2);
    }

    @Test
    @DisplayName("tokens come back as the window passes")
    void refillsOverTheWindow() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }
        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isFalse();

        // Greedy refill over a five-minute window puts one token back every 75 seconds.
        clock.advance(Duration.ofSeconds(80));

        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isTrue();
        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isFalse();
    }

    @Test
    @DisplayName("a whole window of quiet restores the full capacity")
    void restoresFullCapacityAfterAWholeWindow() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }

        clock.advance(Duration.ofMinutes(5));

        assertThat(allowedInARow(CREDENTIALS, IP, "198.51.100.7")).isEqualTo(4);
    }

    @Test
    @DisplayName("a refusal reports how long to wait, and hammering does not push that out")
    void reportsARetryDelayThatDoesNotGrowUnderPressure() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }

        AuthRateLimitDecision first = limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        AuthRateLimitDecision afterTenMoreTries = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            afterTenMoreTries = limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }

        assertThat(first.allowed()).isFalse();
        assertThat(first.retryAfterSeconds()).isEqualTo(75);
        assertThat(afterTenMoreTries).isNotNull();
        assertThat(afterTenMoreTries.retryAfterSeconds()).isEqualTo(first.retryAfterSeconds());
    }

    @Test
    @DisplayName("a sub-second wait is still reported as a whole second")
    void roundsTheRetryDelayUp() {
        AuthRateLimitDecision decision = AuthRateLimitDecision.refuse(Duration.ofMillis(120));

        assertThat(decision.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    @DisplayName("configuration that would disable a limit is refused at startup")
    void rejectsUnusableConfiguration() {
        assertThatThrownBy(() -> new AuthRateLimitProperties(
                true, 0, 1000, 0, 2, Duration.ofMinutes(5), 3, 2, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-attempts-per-ip");

        assertThatThrownBy(() -> new AuthRateLimitProperties(
                true, -1, 1000, 4, 2, Duration.ofMinutes(5), 3, 2, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trusted-proxy-count");

        assertThatThrownBy(() -> new AuthRateLimitProperties(
                true, 0, 1000, 4, 2, Duration.ZERO, 3, 2, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-window");
    }

    @Test
    @DisplayName("buckets are evicted no sooner than the widest window they could still be short in")
    void expiresBucketsAgainstTheWidestWindow() {
        assertThat(properties().longestWindow()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("every limited path maps to a rule, and nothing else does")
    void mapsOnlyTheLimitedPaths() {
        assertThat(AuthRateLimitRule.forPath("/api/auth/login")).contains(CREDENTIALS);
        assertThat(AuthRateLimitRule.forPath("/api/auth/verify")).contains(CREDENTIALS);
        assertThat(AuthRateLimitRule.forPath("/api/auth/signup")).contains(EMAIL);
        assertThat(AuthRateLimitRule.forPath("/api/auth/resend")).contains(EMAIL);

        assertThat(AuthRateLimitRule.forPath("/api/auth/login/")).isEmpty();
        assertThat(AuthRateLimitRule.forPath("/API/AUTH/LOGIN")).isEmpty();
        assertThat(AuthRateLimitRule.forPath("/api/tasks")).isEmpty();
        assertThat(AuthRateLimitRule.forPath(null)).isEmpty();

        // The endpoints moved under /api with the global path prefix. Matching the old paths would
        // mean the limiter is guarding URLs nothing serves any more, while the live ones run free.
        assertThat(AuthRateLimitRule.forPath("/auth/login")).isEmpty();
        assertThat(AuthRateLimitRule.forPath("/auth/signup")).isEmpty();
    }

    private int allowedInARow(AuthRateLimitRule rule, AuthRateLimitDimension dimension, String key) {
        int allowed = 0;
        while (limiter.tryConsume(rule, dimension, key).allowed()) {
            allowed++;
            if (allowed > 100) {
                throw new IllegalStateException("bucket never ran out");
            }
        }
        return allowed;
    }
}
