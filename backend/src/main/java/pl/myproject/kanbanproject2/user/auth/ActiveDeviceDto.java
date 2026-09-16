package pl.myproject.kanbanproject2.user.auth;

import java.time.Instant;

/**
 * One live session, as the person who owns it sees it. What is deliberately absent is the token,
 * in either form — the {@code id} is what the revoke route takes, and is useless to anyone not
 * already signed in as this account, since the service answers {@code 404} rather than {@code 403}
 * for somebody else's row.
 *
 * <p>{@code signedInAt} and {@code lastSeenAt} are different instants because a chain rotates on
 * every renewal, so the row's own age says only when the browser last asked for a new access
 * token — the sign-in is the fact somebody scanning this list is actually checking.
 * {@code lastSeenAt} is therefore accurate to the renewal rather than the request, up to one
 * access-token lifetime stale, since recording it per request would mean a write on every call for
 * a column nobody reads more precisely than "today".
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
