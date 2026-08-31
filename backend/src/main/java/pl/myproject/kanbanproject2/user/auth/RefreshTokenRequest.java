package pl.myproject.kanbanproject2.user.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * The body {@code /auth/refresh} and {@code /auth/logout} take.
 *
 * <p>The token travels in the body rather than in an {@code Authorization} header on purpose: the
 * header is where the access token lives, and a route that accepted either would be a route where
 * presenting the wrong one is a typo rather than a rejection. It carries no {@code email}, so the
 * rate limiter charges these two routes against the caller's address only - which is the right
 * bucket anyway, since a refresh token names no account until it has been looked up.
 */
public record RefreshTokenRequest(
        @NotBlank(message = "Refresh token is required") String refreshToken
) {
}
