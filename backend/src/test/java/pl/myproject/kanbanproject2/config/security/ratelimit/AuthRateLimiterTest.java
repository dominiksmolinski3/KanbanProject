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

/**
 * The test properties give CREDENTIALS four free attempts per address and two per account, and
 * EMAIL three per address and two per account, with a fifteen-second base cooldown throughout.
 */
class AuthRateLimiterTest {

    private final AuthRateLimitTestSupport.FakeClock clock = new AuthRateLimitTestSupport.FakeClock();
    private final AuthRateLimiter limiter = new AuthRateLimiter(properties(), clock);

    @Test
    @DisplayName("a key is allowed through its burst and asked to wait after it")
    void refusesOnceTheBurstIsSpent() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed())
                    .as("attempt %d", attempt)
                    .isTrue();
        }

        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isFalse();
    }

    @Test
    @DisplayName("the wait doubles each time it is spent, and stops at the ceiling")
    void theWaitDoublesUpToTheCeiling() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }

        // Each retry is taken exactly when the previous wait runs out, which is what a person
        // watching a countdown does - so the next one is the next rung of the ladder.
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(15);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(30);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(60);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(120);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(240);
        // 480 would be next, but the test ceiling is five minutes.
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(300);
        assertThat(waitAfterSittingOutTheCooldown()).isEqualTo(300);
    }

    @Test
    @DisplayName("the first wait a person meets is fifteen seconds, not the rest of the hour")
    void theFirstWaitOnTheEmailLimitIsShort() {
        // The signup that started this: an address that has just asked for two mails asks for a
        // third. It used to be refused for the eleven minutes until a token trickled back.
        limiter.tryConsume(EMAIL, ACCOUNT, "someone@example.test");
        limiter.tryConsume(EMAIL, ACCOUNT, "someone@example.test");

        AuthRateLimitDecision third = limiter.tryConsume(EMAIL, ACCOUNT, "someone@example.test");

        assertThat(third.allowed()).isFalse();
        assertThat(third.retryAfterSeconds()).isEqualTo(15);
    }

    @Test
    @DisplayName("waiting out the cooldown lets the next attempt through")
    void theCooldownLetsGo() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }
        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isFalse();

        clock.advance(Duration.ofSeconds(15));

        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isTrue();
    }

    @Test
    @DisplayName("a refusal reports how long is left, and hammering neither extends it nor shortens it")
    void reportsARetryDelayThatDoesNotMoveUnderPressure() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }

        AuthRateLimitDecision first = limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        clock.advance(Duration.ofSeconds(5));
        AuthRateLimitDecision afterTenMoreTries = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            afterTenMoreTries = limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }

        assertThat(first.retryAfterSeconds()).isEqualTo(15);
        assertThat(afterTenMoreTries).isNotNull();
        assertThat(afterTenMoreTries.allowed()).isFalse();
        // Five seconds of hammering later, five seconds less to wait - the countdown is the clock's,
        // not a punishment that restarts on every try.
        assertThat(afterTenMoreTries.retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    @DisplayName("a whole quiet window forgives the escalation and hands the burst back")
    void aQuietWindowForgivesTheKey() {
        for (int attempt = 0; attempt < 6; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }
        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isFalse();

        clock.advance(Duration.ofMinutes(15));

        assertThat(allowedInARow(CREDENTIALS, IP, "198.51.100.7")).isEqualTo(4);
    }

    @Test
    @DisplayName("quiet means quiet: a key kept warm by refused attempts is never forgiven")
    void hammeringKeepsTheEscalationAlive() {
        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }

        // Twenty minutes of trying once a minute - longer than the fifteen-minute window, but never
        // fifteen quiet minutes, so the escalation stands rather than resetting under the traffic.
        for (int minute = 0; minute < 20; minute++) {
            clock.advance(Duration.ofMinutes(1));
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
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
    @DisplayName("the same value on two dimensions is tracked separately")
    void dimensionsAreIndependent() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "shared-value");
        }

        assertThat(limiter.tryConsume(CREDENTIALS, IP, "shared-value").allowed()).isFalse();
        assertThat(limiter.tryConsume(CREDENTIALS, ACCOUNT, "shared-value").allowed()).isTrue();
    }

    @Test
    @DisplayName("the same value under two rules is tracked separately")
    void rulesAreIndependent() {
        for (int attempt = 0; attempt < 4; attempt++) {
            limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        }

        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isFalse();
        assertThat(limiter.tryConsume(EMAIL, IP, "198.51.100.7").allowed()).isTrue();
    }

    @Test
    @DisplayName("each rule and dimension enforces its own burst")
    void eachLimitUsesItsOwnBurst() {
        assertThat(allowedInARow(CREDENTIALS, IP, "a")).isEqualTo(4);
        assertThat(allowedInARow(CREDENTIALS, ACCOUNT, "a")).isEqualTo(2);
        assertThat(allowedInARow(EMAIL, IP, "a")).isEqualTo(3);
        assertThat(allowedInARow(EMAIL, ACCOUNT, "a")).isEqualTo(2);
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
        assertThatThrownBy(() -> withCredentialLimits(0, 2, Duration.ofSeconds(15), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-attempts-per-ip");

        assertThatThrownBy(() -> withCredentialLimits(4, 2, Duration.ZERO, Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-base-cooldown");
    }

    @Test
    @DisplayName("a ceiling below the base, or a window below the ceiling, is refused too")
    void rejectsAnEscalationThatCouldNotEscalate() {
        // A ceiling under the base would make the first wait the longest one there is.
        assertThatThrownBy(() -> withCredentialLimits(4, 2, Duration.ofMinutes(1), Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-max-cooldown");

        // A window under the ceiling would forgive a key while it is still serving its longest
        // wait, so sitting one out would hand the whole burst back.
        assertThatThrownBy(() -> new AuthRateLimitProperties(
                true, 0, 1000,
                4, 2, Duration.ofSeconds(15), Duration.ofMinutes(30), Duration.ofMinutes(15),
                3, 2, Duration.ofSeconds(15), Duration.ofMinutes(15), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-window");
    }

    @Test
    @DisplayName("keys are evicted no sooner than the widest window they could still be held by")
    void expiresKeysAgainstTheWidestWindow() {
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

    /** Takes the attempt the moment its cooldown expires, and answers the next wait in seconds. */
    private long waitAfterSittingOutTheCooldown() {
        AuthRateLimitDecision refused = limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7");
        assertThat(refused.allowed()).isFalse();
        clock.advance(Duration.ofSeconds(refused.retryAfterSeconds()));
        assertThat(limiter.tryConsume(CREDENTIALS, IP, "198.51.100.7").allowed()).isTrue();
        return refused.retryAfterSeconds();
    }

    private int allowedInARow(AuthRateLimitRule rule, AuthRateLimitDimension dimension, String key) {
        int allowed = 0;
        while (limiter.tryConsume(rule, dimension, key).allowed()) {
            allowed++;
            if (allowed > 100) {
                throw new IllegalStateException("the limiter never refused");
            }
        }
        return allowed;
    }

    private static AuthRateLimitProperties withCredentialLimits(
            long credentialAttemptsPerIp, long credentialAttemptsPerAccount, Duration base, Duration max) {

        return new AuthRateLimitProperties(
                true, 0, 1000,
                credentialAttemptsPerIp, credentialAttemptsPerAccount, base, max, Duration.ofMinutes(15),
                3, 2, Duration.ofSeconds(15), Duration.ofMinutes(15), Duration.ofHours(1));
    }
}
