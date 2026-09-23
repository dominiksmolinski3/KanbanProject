package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "security.rate-limit")
public record AuthRateLimitProperties(

        @DefaultValue("true") boolean enabled,

        @DefaultValue("0") int trustedProxyCount,

        @DefaultValue("20000") long maxTrackedKeys,

        @DefaultValue("15") long credentialAttemptsPerIp,
        @DefaultValue("5") long credentialAttemptsPerAccount,
        @DefaultValue("15s") Duration credentialBaseCooldown,
        @DefaultValue("5m") Duration credentialMaxCooldown,
        @DefaultValue("15m") Duration credentialWindow,

        @DefaultValue("5") long emailRequestsPerIp,
        @DefaultValue("3") long emailRequestsPerAccount,
        @DefaultValue("15s") Duration emailBaseCooldown,
        @DefaultValue("15m") Duration emailMaxCooldown,
        @DefaultValue("1h") Duration emailWindow,

        @DefaultValue("localhost") String redisHost,
        @DefaultValue("6379") int redisPort,
        @DefaultValue("") String redisPassword,
        @DefaultValue("false") boolean redisSsl
) {

    public AuthRateLimitProperties {
        if (trustedProxyCount < 0) {
            throw new IllegalArgumentException("security.rate-limit.trusted-proxy-count must not be negative");
        }
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException("security.rate-limit.max-tracked-keys must be at least 1");
        }
        requirePositive(credentialAttemptsPerIp, "credential-attempts-per-ip");
        requirePositive(credentialAttemptsPerAccount, "credential-attempts-per-account");
        requireEscalation(credentialBaseCooldown, credentialMaxCooldown, credentialWindow, "credential");
        requirePositive(emailRequestsPerIp, "email-requests-per-ip");
        requirePositive(emailRequestsPerAccount, "email-requests-per-account");
        requireEscalation(emailBaseCooldown, emailMaxCooldown, emailWindow, "email");
        if (redisHost == null || redisHost.isBlank()) {
            throw new IllegalArgumentException("security.rate-limit.redis-host must not be blank");
        }
        if (redisPort < 1 || redisPort > 65535) {
            throw new IllegalArgumentException("security.rate-limit.redis-port must be a valid port number");
        }
    }

    Duration longestWindow() {
        return credentialWindow.compareTo(emailWindow) >= 0 ? credentialWindow : emailWindow;
    }

    private static void requireEscalation(Duration base, Duration max, Duration window, String prefix) {
        requirePositive(base, prefix + "-base-cooldown");
        requirePositive(max, prefix + "-max-cooldown");
        requirePositive(window, prefix + "-window");
        if (max.compareTo(base) < 0) {
            throw new IllegalArgumentException(
                    "security.rate-limit." + prefix + "-max-cooldown must be at least the base cooldown");
        }
        if (window.compareTo(max) < 0) {
            throw new IllegalArgumentException(
                    "security.rate-limit." + prefix + "-window must be at least the max cooldown");
        }
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException("security.rate-limit." + name + " must be at least 1");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("security.rate-limit." + name + " must be positive");
        }
    }
}
