package pl.myproject.kanbanproject2.messaging;

import java.time.Instant;

public record CryptoDeviceDto(Long id, Long sessionId, byte[] signatureKey, String label, Instant createdAt,
                              Instant lastSeenAt, long keyPackages) {
}
