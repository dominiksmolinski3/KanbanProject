package pl.myproject.kanbanproject2.config.security;

/**
 * What a successful login, and every refresh after it, hands back. {@code token} is the short,
 * unwithdrawable access token every request carries; {@code refreshToken} is a withdrawable
 * database row, long-lived, which is what keeps a short access token from meaning "sign in again
 * every fifteen minutes". {@code sessionId} names that row so the client can recognise itself in
 * its own session list - marking "this device" server-side would cost a claim, a filter change and
 * a lookup on every request for a fact the client already has.
 */
public record LoginResponse(String token, long expiresIn, String refreshToken, long refreshExpiresIn,
                            Long sessionId) {
}
