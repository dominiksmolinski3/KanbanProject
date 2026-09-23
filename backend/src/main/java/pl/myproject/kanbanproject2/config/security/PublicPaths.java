package pl.myproject.kanbanproject2.config.security;

import org.springframework.util.AntPathMatcher;

public final class PublicPaths {
    public static final String[] AUTH_ENDPOINTS = {
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/verify",
            "/api/auth/resend",
            "/api/auth/forgot-password",
            "/api/auth/reset-password",
            "/api/auth/refresh",
            "/api/auth/logout"
    };

    public static final String[] INFRA_ENDPOINTS = {
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/ws/**",
            "/error"
    };

    public static final String[] DOCS_ENDPOINTS = {
            "/v3/api-docs",
            "/v3/api-docs/**"
    };

    public static final String[] WEBHOOK_ENDPOINTS = {
            "/api/mail/delivery-reports"
    };

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private PublicPaths() {
    }

    public static boolean isPublic(String path) {
        return matchesAny(AUTH_ENDPOINTS, path)
                || matchesAny(INFRA_ENDPOINTS, path)
                || matchesAny(DOCS_ENDPOINTS, path)
                || matchesAny(WEBHOOK_ENDPOINTS, path);
    }

    private static boolean matchesAny(String[] patterns, String path) {
        for (String pattern : patterns) {
            if (MATCHER.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }
}
