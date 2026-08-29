package pl.myproject.kanbanproject2.config.security;

import org.springframework.util.AntPathMatcher;

/**
 * The single list of paths reachable without a token, in the same form the filter chain uses.
 *
 * <p>These lived as private arrays on {@link SecurityConfiguration} while
 * {@link JwtAuthenticationFilter} kept a second, hand-rolled copy — which promptly drifted: the
 * filter still skipped {@code /auth/**} after the {@code /api} prefix landed, and skipped anything
 * whose path merely <em>ended</em> in a static-asset extension, which a free-text label segment
 * can. Both sides read this class now so the two cannot disagree again.
 *
 * <p>Patterns are Ant-style, matched against the path below the context root, exactly as
 * {@code requestMatchers(String...)} does — so {@code *} does not cross a {@code /} here either.
 */
public final class PublicPaths {

    public static final String[] AUTH_ENDPOINTS = {
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/verify",
            "/api/auth/resend",
            // Someone who has forgotten their password has, by definition, no token.
            "/api/auth/forgot-password",
            "/api/auth/reset-password"
    };

    public static final String[] INFRA_ENDPOINTS = {
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/ws/**",
            "/error"
    };

    /*
     * Everything a browser fetches before it holds a token.
     *
     * The single-segment patterns cover the files Vite copies to the bundle root (favicon, logo);
     * the two directory patterns are the ones that actually matter, because `*` does not cross a
     * `/` and the app's own code does not sit at the root. Vite emits the bundle to
     * `/assets/index-<hash>.js`, and i18next fetches `/locales/<lang>/translation.json` at runtime.
     * Without both, the container serves index.html and then 403s the script that would boot it.
     */
    public static final String[] STATIC_ASSETS = {
            "/assets/**", "/locales/**",
            "/*.html", "/*.js", "/*.css", "/*.ico", "/*.json",
            "/*.png", "/*.svg", "/*.jpg", "/*.jpeg", "/*.gif",
            "/*.webp", "/*.woff", "/*.woff2", "/*.ttf"
    };

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private PublicPaths() {
    }

    /**
     * Whether the given path is served without authentication, and so has nothing for
     * {@link JwtAuthenticationFilter} to do. The SPA shell is deliberately absent: those routes
     * are permitted by the chain, and a signed-in user loading one should still have their token
     * resolved.
     */
    public static boolean isPublic(String path) {
        return matchesAny(AUTH_ENDPOINTS, path)
                || matchesAny(INFRA_ENDPOINTS, path)
                || matchesAny(STATIC_ASSETS, path);
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
