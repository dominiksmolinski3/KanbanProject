package pl.myproject.kanbanproject2.user.auth;

import java.time.Instant;

public record ActiveDeviceDto(
        Long id,
        String ipAddress,
        String userAgent,
        Instant signedInAt,
        Instant lastSeenAt,
        Instant expiresAt
) {
}
