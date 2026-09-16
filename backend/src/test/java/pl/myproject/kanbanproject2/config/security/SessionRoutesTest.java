package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimitRule;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three ways refresh tokens could go back to being decorative, checked at build time: a route
 * that is not reachable ({@code /auth/refresh} and {@code /auth/logout} must stay public, or they
 * are useless exactly when needed), a public route with no limit (both present a secret, the shape
 * {@link AuthRateLimitRule#CREDENTIALS} exists for), and the login response quietly dropping its
 * refresh token, which would read as "sessions are short" rather than as a bug.
 */
class SessionRoutesTest {

    private static final String REFRESH = "/api/auth/refresh";
    private static final String LOGOUT = "/api/auth/logout";
    private static final String DEVICES = "/api/auth/devices";

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
    @DisplayName("listing and ending a device are not public - they are the two that need a token")
    void theDeviceRoutesAreNotPublic() {
        assertThat(PublicPaths.AUTH_ENDPOINTS)
                .as("every other route under /auth exists for someone who cannot prove who they "
                        + "are yet; these two are about an account, so proving it is the whole "
                        + "precondition")
                .doesNotContain(DEVICES);
        assertThat(PublicPaths.isPublic(DEVICES)).isFalse();
        assertThat(PublicPaths.isPublic(DEVICES + "/12")).isFalse();
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

    @Test
    @DisplayName("the login response names the session it started, or the client cannot find itself in the list")
    void theLoginResponseNamesItsOwnSession() {
        var components = Arrays.stream(LoginResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(components)
                .as("marking the current device is done by comparing this id to the listing; "
                        + "without it every row looks like somebody else's browser")
                .contains("sessionId");
    }
}
