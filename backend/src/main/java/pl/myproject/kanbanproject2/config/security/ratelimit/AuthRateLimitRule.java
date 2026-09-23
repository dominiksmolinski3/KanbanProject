package pl.myproject.kanbanproject2.config.security.ratelimit;

import java.util.Map;
import java.util.Optional;

public enum AuthRateLimitRule {
    CREDENTIALS,

    EMAIL;

    static final String LOGIN_PATH = "/api/auth/login";
    static final String VERIFY_PATH = "/api/auth/verify";
    static final String SIGNUP_PATH = "/api/auth/signup";
    static final String RESEND_PATH = "/api/auth/resend";
    static final String FORGOT_PASSWORD_PATH = "/api/auth/forgot-password";
    static final String RESET_PASSWORD_PATH = "/api/auth/reset-password";
    static final String REFRESH_PATH = "/api/auth/refresh";
    static final String LOGOUT_PATH = "/api/auth/logout";

    private static final Map<String, AuthRateLimitRule> BY_PATH = Map.of(
            LOGIN_PATH, CREDENTIALS,
            VERIFY_PATH, CREDENTIALS,
            SIGNUP_PATH, EMAIL,
            RESEND_PATH, EMAIL,
            FORGOT_PASSWORD_PATH, EMAIL,
            RESET_PASSWORD_PATH, CREDENTIALS,
            REFRESH_PATH, CREDENTIALS,
            LOGOUT_PATH, CREDENTIALS
    );

    public static Optional<AuthRateLimitRule> forPath(String path) {
        return Optional.ofNullable(path).map(BY_PATH::get);
    }
}
