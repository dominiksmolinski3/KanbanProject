package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployed app is configured entirely through environment variables, and a name that does not
 * bind fails silently — the limiter would keep the default and nothing would say so. These tests
 * pin the names, including the one
 * {@code terraform/modules/container_app/main.tf} sets.
 */
class AuthRateLimitPropertiesBindingTest {

    @Test
    @DisplayName("with nothing configured the limiter is on and trusts no proxy")
    void defaultsAreSafe() {
        AuthRateLimitProperties properties = bind(Map.of());

        assertThat(properties.enabled()).isTrue();
        // Trusting a proxy that is not there would let a caller pick their own bucket by sending
        // their own X-Forwarded-For, so this has to default off and be turned on per deployment.
        assertThat(properties.trustedProxyCount()).isZero();
    }

    @Test
    @DisplayName("SECURITY_RATE_LIMIT_TRUSTED_PROXY_COUNT is the name the Container App env block sets")
    void bindsTheTrustedProxyCountFromTheEnvironment() {
        AuthRateLimitProperties properties = bind(Map.of("SECURITY_RATE_LIMIT_TRUSTED_PROXY_COUNT", "1"));

        assertThat(properties.trustedProxyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("every limit can be retuned from the environment without a rebuild")
    void bindsEveryLimitFromTheEnvironment() {
        AuthRateLimitProperties properties = bind(Map.ofEntries(
                Map.entry("SECURITY_RATE_LIMIT_ENABLED", "false"),
                Map.entry("SECURITY_RATE_LIMIT_MAX_TRACKED_KEYS", "500"),
                Map.entry("SECURITY_RATE_LIMIT_CREDENTIAL_ATTEMPTS_PER_IP", "7"),
                Map.entry("SECURITY_RATE_LIMIT_CREDENTIAL_ATTEMPTS_PER_ACCOUNT", "6"),
                Map.entry("SECURITY_RATE_LIMIT_CREDENTIAL_BASE_COOLDOWN", "20s"),
                Map.entry("SECURITY_RATE_LIMIT_CREDENTIAL_MAX_COOLDOWN", "4m"),
                Map.entry("SECURITY_RATE_LIMIT_CREDENTIAL_WINDOW", "90m"),
                Map.entry("SECURITY_RATE_LIMIT_EMAIL_REQUESTS_PER_IP", "5"),
                Map.entry("SECURITY_RATE_LIMIT_EMAIL_REQUESTS_PER_ACCOUNT", "4"),
                Map.entry("SECURITY_RATE_LIMIT_EMAIL_BASE_COOLDOWN", "10s"),
                Map.entry("SECURITY_RATE_LIMIT_EMAIL_MAX_COOLDOWN", "20m"),
                Map.entry("SECURITY_RATE_LIMIT_EMAIL_WINDOW", "2h")));

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.maxTrackedKeys()).isEqualTo(500);
        assertThat(properties.credentialAttemptsPerIp()).isEqualTo(7);
        assertThat(properties.credentialAttemptsPerAccount()).isEqualTo(6);
        assertThat(properties.credentialBaseCooldown()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.credentialMaxCooldown()).isEqualTo(Duration.ofMinutes(4));
        assertThat(properties.credentialWindow()).isEqualTo(Duration.ofMinutes(90));
        assertThat(properties.emailRequestsPerIp()).isEqualTo(5);
        assertThat(properties.emailRequestsPerAccount()).isEqualTo(4);
        assertThat(properties.emailBaseCooldown()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.emailMaxCooldown()).isEqualTo(Duration.ofMinutes(20));
        assertThat(properties.emailWindow()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    @DisplayName("the shipped defaults are the escalation, not a quota")
    void defaultsAreTheEscalation() {
        AuthRateLimitProperties properties = bind(Map.of());

        // The numbers a deployment gets without saying anything: a burst, then fifteen seconds,
        // then thirty, and so on to a ceiling.
        assertThat(properties.credentialBaseCooldown()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.emailBaseCooldown()).isEqualTo(Duration.ofSeconds(15));
        assertThat(properties.credentialMaxCooldown()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.emailMaxCooldown()).isEqualTo(Duration.ofMinutes(15));
    }

    private static AuthRateLimitProperties bind(Map<String, Object> environmentVariables) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));

        return Binder.get(environment)
                .bindOrCreate("security.rate-limit", AuthRateLimitProperties.class);
    }
}
