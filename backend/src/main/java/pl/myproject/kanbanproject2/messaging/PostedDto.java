package pl.myproject.kanbanproject2.messaging;

import java.time.Instant;

public record PostedDto(long seq, long epoch, Instant createdAt) {
}
