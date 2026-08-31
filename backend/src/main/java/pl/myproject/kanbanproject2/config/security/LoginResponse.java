package pl.myproject.kanbanproject2.config.security;

/**
 * What a successful login, and every refresh after it, hands back.
 *
 * <p>Two tokens with two very different jobs. {@code token} is the signed access token every
 * request carries; it cannot be withdrawn, so it is short. {@code refreshToken} is a row in the
 * database that can be withdrawn, so it is long, and it is the only thing that keeps a short access
 * token from meaning "sign in again every fifteen minutes".
 *
 * <p>Both expiries are milliseconds, matching what the client already stored for the access token.
 */
public record LoginResponse(String token, long expiresIn, String refreshToken, long refreshExpiresIn) {
}
