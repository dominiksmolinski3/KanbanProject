package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitRule;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three ways refresh tokens could go back to being decorative, checked at build time.
 *
 * <p>The first is a route that is not reachable. {@code /auth/refresh} exists for the caller whose
 * access token has just lapsed, so requiring one would make it useless precisely when it is needed;
 * {@code /auth/logout} has the same problem from the other end, since a session nobody can end is
 * the state this whole change was about.
 *
 * <p>The second is a public route with no limit. Both of these present a secret, which is the shape
 * {@link AuthRateLimitRule#CREDENTIALS} exists for - and the limiter is opt-in by path, so a route
 * is unprotected until somebody lists it.
 *
 * <p>The third is the quiet one: the login response dropping its refresh token. The client would go
 * on signing in and would simply never be able to renew, which reads as "sessions are short" rather
 * than as a bug, and would have looked exactly like the behaviour before this existed.
 */
class SessionRoutesTest {

    private static final String REFRESH = "/api/auth/refresh";
    private static final String LOGOUT = "/api/auth/logout";

    @Test
    @DisplayName("both routes are reachable without an access token, or neither could ever be used")
    void bothRoutesArePublic() {
        assertThat(PublicPaths.AUTH_ENDPOINTS).contains(REFRESH, LOGOUT);
        assertThat(PublicPaths.isPublic(REFRESH)).isTrue();
        assertThat(PublicPaths.isPublic(LOGOUT)).isTrue();
    }

    @Test
    @DisplayName("both are on the CREDENTIALS limit - each presents a secret and neither sends mail")
    void bothRoutesAreRateLimited() {
        assertThat(AuthRateLimitRule.forPath(REFRESH)).contains(AuthRateLimitRule.CREDENTIALS);
        assertThat(AuthRateLimitRule.forPath(LOGOUT)).contains(AuthRateLimitRule.CREDENTIALS);
    }

    @Test
    @DisplayName("the login response still carries a refresh token and its lifetime")
    void theLoginResponseCarriesTheSession() {
        var components = Arrays.stream(LoginResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(components)
                .as("a login that answers without a refresh token cannot be renewed, and the "
                        + "client cannot tell that from a short session")
                .contains("token", "expiresIn", "refreshToken", "refreshExpiresIn");
    }
}
