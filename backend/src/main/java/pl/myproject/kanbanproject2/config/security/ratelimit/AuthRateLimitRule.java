package pl.myproject.kanbanproject2.config.security.ratelimit;

import java.util.Map;
import java.util.Optional;

/**
 * The two kinds of abuse the public {@code /auth/**} endpoints are open to. They need different
 * limits because they cost different things: a wrong password costs a database round trip, while
 * a signup costs an outbound email against a shared Gmail quota.
 */
public enum AuthRateLimitRule {

    /** Guessing a password or a six-digit verification code. */
    CREDENTIALS,

    /** Making the app send mail on demand, which burns quota and sender reputation. */
    EMAIL;

    static final String LOGIN_PATH = "/auth/login";
    static final String VERIFY_PATH = "/auth/verify";
    static final String SIGNUP_PATH = "/auth/signup";
    static final String RESEND_PATH = "/auth/resend";

    private static final Map<String, AuthRateLimitRule> BY_PATH = Map.of(
            LOGIN_PATH, CREDENTIALS,
            VERIFY_PATH, CREDENTIALS,
            SIGNUP_PATH, EMAIL,
            RESEND_PATH, EMAIL
    );

    /**
     * Exact match only. Spring Security's {@code StrictHttpFirewall} rejects the encoded and
     * path-parameter variants before any filter runs, and Spring MVC will not route a near miss
     * to a controller either, so a request this does not recognise cannot reach a limited handler.
     */
    public static Optional<AuthRateLimitRule> forPath(String path) {
        return Optional.ofNullable(path).map(BY_PATH::get);
    }
}
