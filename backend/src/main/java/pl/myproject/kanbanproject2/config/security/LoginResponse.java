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
 *
 * <p>{@code sessionId} names the row the refresh token was written to, and exists so the client can
 * recognise itself in its own list of sessions. Marking "this device" server-side would mean the
 * access token carrying the session it descends from, which is a claim, a filter change and a
 * lookup on every request; the client already has the fact, because it was handed it here and is
 * handed a new one on every rotation. It is not a credential - it is worth nothing without an
 * access token for the same account - and the revoke route checks ownership regardless.
 */
public record LoginResponse(String token, long expiresIn, String refreshToken, long refreshExpiresIn,
                            Long sessionId) {
}
