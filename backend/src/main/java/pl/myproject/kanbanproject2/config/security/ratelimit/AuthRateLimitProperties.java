package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning knobs for the {@link AuthRateLimitFilter}.
 *
 * <p>The buckets are held in process memory, so every limit below is <em>per replica</em>. With
 * the Container App scaled to five replicas the effective ceiling is five times what is
 * configured here; the defaults are sized with that multiplier already in mind. Moving to a
 * shared store is the only way to make a limit exact across replicas.
 */
@ConfigurationProperties(prefix = "security.rate-limit")
public record AuthRateLimitProperties(

        /* Turns the filter off entirely. Kept so a limit that misfires can be dropped without a rollback. */
        @DefaultValue("true") boolean enabled,

        /*
         * How many reverse proxies sit in front of the app, counted from the app outwards.
         *
         * 0 (the default) ignores X-Forwarded-For completely and keys on the socket address,
         * which is correct for docker-compose and for `npm run dev` behind the Vite proxy.
         * Container Apps ingress is exactly one hop, so a deployment behind it must set 1;
         * adding Front Door in front of that would make it 2.
         *
         * The default is deliberately the restrictive one: an over-low count buckets more
         * traffic together than intended, while an over-high count would let a client forge
         * its own key and walk away from the limiter entirely.
         */
        @DefaultValue("0") int trustedProxyCount,

        /* Upper bound on live buckets, so attacker-chosen keys cannot exhaust the 0.5Gi heap. */
        @DefaultValue("20000") long maxTrackedKeys,

        /* Guessing attacks against /auth/login and /auth/verify. */
        @DefaultValue("30") long credentialAttemptsPerIp,
        @DefaultValue("10") long credentialAttemptsPerAccount,
        @DefaultValue("5m") Duration credentialWindow,

        /* Outbound-mail amplification through /auth/signup and /auth/resend. */
        @DefaultValue("10") long emailRequestsPerIp,
        @DefaultValue("5") long emailRequestsPerAccount,
        @DefaultValue("1h") Duration emailWindow
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
        requirePositive(credentialWindow, "credential-window");
        requirePositive(emailRequestsPerIp, "email-requests-per-ip");
        requirePositive(emailRequestsPerAccount, "email-requests-per-account");
        requirePositive(emailWindow, "email-window");
    }

    /**
     * The longest window in play. Buckets idle for at least this long have refilled to capacity,
     * so evicting one after that is indistinguishable from keeping it.
     */
    Duration longestWindow() {
        return credentialWindow.compareTo(emailWindow) >= 0 ? credentialWindow : emailWindow;
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
