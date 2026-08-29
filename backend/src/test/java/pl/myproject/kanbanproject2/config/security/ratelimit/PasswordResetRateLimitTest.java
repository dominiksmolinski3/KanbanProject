package pl.myproject.kanbanproject2.config.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.config.security.PublicPaths;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The password reset routes are unauthenticated and one of them sends mail on demand, which is the
 * exact shape the limiter exists for. A public endpoint that mails on request and is <em>not</em>
 * limited is a way to burn a shared Gmail quota and a sender reputation from a script - and the
 * limiter is opt-in by path, so a new route is unprotected until someone lists it.
 */
class PasswordResetRateLimitTest {

    @Test
    @DisplayName("asking for a reset is on the EMAIL limit, like signup and resend")
    void forgotPasswordIsRateLimitedAsEmail() {
        assertThat(AuthRateLimitRule.forPath(AuthRateLimitRule.FORGOT_PASSWORD_PATH))
                .contains(AuthRateLimitRule.EMAIL);
    }

    @Test
    @DisplayName("redeeming a code is on the CREDENTIALS limit - it is a six-digit secret being guessed")
    void resetPasswordIsRateLimitedAsCredentials() {
        assertThat(AuthRateLimitRule.forPath(AuthRateLimitRule.RESET_PASSWORD_PATH))
                .contains(AuthRateLimitRule.CREDENTIALS);
    }

    @Test
    @DisplayName("every public auth endpoint is covered by a limit - none is left off the list")
    void everyPublicAuthEndpointIsLimited() {
        var unlimited = Arrays.stream(PublicPaths.AUTH_ENDPOINTS)
                .filter(path -> AuthRateLimitRule.forPath(path).isEmpty())
                .toList();

        assertThat(unlimited)
                .as("an unauthenticated endpoint with no rate limit; add it to AuthRateLimitRule")
                .isEmpty();
    }

    @Test
    @DisplayName("both new routes are reachable without a token, or nobody who forgot could use them")
    void bothRoutesArePublic() {
        assertThat(PublicPaths.AUTH_ENDPOINTS)
                .contains(AuthRateLimitRule.FORGOT_PASSWORD_PATH, AuthRateLimitRule.RESET_PASSWORD_PATH);
    }

    @Test
    @DisplayName("changing a password is not public - it is an authenticated write on an account")
    void changingAPasswordIsNotPublic() {
        assertThat(PublicPaths.AUTH_ENDPOINTS)
                .noneMatch(path -> path.contains("password") && path.contains("/users/"));
    }
}
