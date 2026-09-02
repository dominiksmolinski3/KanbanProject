package pl.myproject.kanbanproject2.user.auth;

import java.time.Instant;

/**
 * One live session, as the person who owns it sees it.
 *
 * <p>What is deliberately absent is the token, in either form. The digest is a credential's
 * fingerprint and this route hands the list to a browser; the {@code id} is what the revoke route
 * takes, and it is useless to anyone who is not already signed in as this account — the service
 * checks ownership and answers {@code 404} rather than {@code 403} for somebody else's row, which
 * is the same reasoning every board route follows.
 *
 * <p>{@code signedInAt} and {@code lastSeenAt} are two different instants for a reason. A chain
 * rotates on every renewal, roughly every fifteen minutes of use, so the row's own age says only
 * when the browser last asked for a new access token. The sign-in is the fact somebody scanning
 * this list is actually checking.
 *
 * <p>{@code lastSeenAt} is therefore accurate to the renewal rather than to the request: a session
 * used continuously is up to one access-token lifetime stale here. Recording it per request would
 * mean a write on every call, which is a large cost for a column nobody reads more precisely than
 * "today".
 */
public record ActiveDeviceDto(
        Long id,
        String ipAddress,
        String userAgent,
        Instant signedInAt,
        Instant lastSeenAt,
        Instant expiresAt
) {
}
