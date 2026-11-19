package pl.myproject.kanbanproject2.messaging;

import java.time.Instant;

public record IdentityKeyDto(byte[] publicKey, Instant createdAt, boolean recoveryKey) {
}
