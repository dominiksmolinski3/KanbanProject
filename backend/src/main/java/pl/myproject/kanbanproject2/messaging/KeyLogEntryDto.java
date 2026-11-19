package pl.myproject.kanbanproject2.messaging;

import java.time.Instant;

public record KeyLogEntryDto(Long id, KeyChange change, byte[] subject, byte[] prevHash, byte[] entryHash,
                             Instant at) {
}
