package pl.myproject.kanbanproject2.config.security;

import org.springframework.util.AntPathMatcher;

/**
 * The single list of paths reachable without a token, in the same form the filter chain uses.
 * {@link SecurityConfiguration} and {@link JwtAuthenticationFilter} used to each hold a copy and
 * had drifted; both read this class now so they cannot disagree again. Patterns are Ant-style,
 * matched exactly as {@code requestMatchers(String...)} does.
 */
public final class PublicPaths {

    public static final String[] AUTH_ENDPOINTS = {
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/verify",
            "/api/auth/resend",
            // Someone who has forgotten their password has, by definition, no token.
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

    /*
     * The published contract - public because publishing it is the point, not because a browser
     * needs it before signing in. A contract needing a token is not published: a generator reads it
     * before having an account, and every route it describes checks its own caller anyway. Neither
     * pattern carries the /api prefix, because WebConfig scopes that to this application's package.
     */
    public static final String[] DOCS_ENDPOINTS = {
            "/v3/api-docs",
            "/v3/api-docs/**"
    };

    /*
     * There is deliberately no list of static assets here any more: the bundle is served by nginx
     * in its own container and never reaches this filter chain. PublicChainPathsTest asserts the
     * absence, so putting any back is a deliberate act.
     */

    /*
     * The one unauthenticated write in the application, public because Azure Event Grid holds no
     * account here and can only call a URL it was given - so the route carries a shared key in its
     * query string and answers 404 without it, which means every request on a fresh clone or in CI
     * where no key is configured. Deliberately not under /api/auth: it is not a credential route
     * and the rate limiter does not cover it.
     */
    public static final String[] WEBHOOK_ENDPOINTS = {
            "/api/mail/delivery-reports"
    };

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private PublicPaths() {
    }

    /**
     * Whether the given path is served without authentication, and so has nothing for
     * {@link JwtAuthenticationFilter} to do.
     */
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
