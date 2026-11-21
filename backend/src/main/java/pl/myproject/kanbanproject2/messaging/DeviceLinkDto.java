package pl.myproject.kanbanproject2.messaging;

import java.time.Instant;
import java.util.UUID;

public record DeviceLinkDto(UUID id, byte[] devicePublic, byte[] linkerPublic, byte[] sealed, Instant expiresAt) {
}
