package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning knobs for the {@link AuthRateLimitFilter}.
 *
 * <p>The limits are a burst of free attempts followed by a cooldown that doubles each time it is
 * spent - fifteen seconds, thirty, a minute, and so on to a ceiling. That shape replaced a fixed
 * quota per window, which had the property that the person it hurt most was the one who had done
 * nothing wrong: five signup mails in a row, the fifth of them a genuine mistyped address, and the
 * sixth attempt was refused for eleven minutes with nothing to do but wait. Someone attacking the
 * endpoint spends that time cheaply; the person trying to use it does not.
 *
 * <p>Doubling gets to the same place for an attacker without that first cliff. The first
 * inconvenience is fifteen seconds - long enough that scripted guessing is pointless, short enough
 * that a person barely registers it - and sustained abuse converges on the ceiling, which is a
 * lower long-run rate than the quota it replaced.
 *
 * <p>The state is held in process memory, so every limit below is <em>per replica</em>. With the
 * Container App scaled to five replicas the effective ceiling is five times what is configured
 * here; the defaults are sized with that multiplier already in mind. Moving to a shared store is
 * the only way to make a limit exact across replicas.
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

        /* Upper bound on live keys, so attacker-chosen ones cannot exhaust the 0.5Gi heap. */
        @DefaultValue("20000") long maxTrackedKeys,

        /*
         * Guessing attacks against /auth/login, /auth/verify, /auth/reset-password and the two
         * session routes. Fifteen attempts per address and five per account before the first
         * wait: enough for a person mistyping a password, and enough for a household or an office
         * behind one address to sign in without meeting the limiter at all.
         */
        @DefaultValue("15") long credentialAttemptsPerIp,
        @DefaultValue("5") long credentialAttemptsPerAccount,
        @DefaultValue("15s") Duration credentialBaseCooldown,
        @DefaultValue("5m") Duration credentialMaxCooldown,
        @DefaultValue("15m") Duration credentialWindow,

        /*
         * Outbound-mail amplification through /auth/signup, /auth/resend and /auth/forgot-password.
         * Tighter than credentials because each attempt costs a message against a shared Gmail
         * quota and against the sender reputation of the domain - but the cost of asking once more
         * is now fifteen seconds rather than the rest of the hour.
         */
        @DefaultValue("5") long emailRequestsPerIp,
        @DefaultValue("3") long emailRequestsPerAccount,
        @DefaultValue("15s") Duration emailBaseCooldown,
        @DefaultValue("15m") Duration emailMaxCooldown,
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
        requireEscalation(credentialBaseCooldown, credentialMaxCooldown, credentialWindow, "credential");
        requirePositive(emailRequestsPerIp, "email-requests-per-ip");
        requirePositive(emailRequestsPerAccount, "email-requests-per-account");
        requireEscalation(emailBaseCooldown, emailMaxCooldown, emailWindow, "email");
    }

    /**
     * The longest a key can matter for. Past this it is either quiet enough to have been forgiven
     * or out of cooldown, so evicting it is indistinguishable from keeping it.
     */
    Duration longestWindow() {
        return credentialWindow.compareTo(emailWindow) >= 0 ? credentialWindow : emailWindow;
    }

    /**
     * A window shorter than the ceiling would forgive a key while it is still serving a cooldown,
     * handing back the whole burst to anyone who simply waits out the longest wait they have
     * earned - which is the one thing the escalation is for.
     */
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
