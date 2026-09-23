package pl.myproject.kanbanproject2.user.auth;

import java.time.Instant;

public record ActiveDeviceDto(
        Long id,
        String ipAddress,
        String userAgent,
        Instant signedInAt,
        Instant lastSeenAt,
        Instant expiresAt,
        boolean redacted
) {

    public ActiveDeviceDto withDetailsRedacted() {
        return new ActiveDeviceDto(id, null, null, signedInAt, lastSeenAt, expiresAt, true);
    }
}
