package pl.myproject.kanbanproject2.messaging;

import java.time.Instant;

public record CryptoDeviceDto(Long id, Long sessionId, byte[] signatureKey, Instant createdAt, long keyPackages) {
}
