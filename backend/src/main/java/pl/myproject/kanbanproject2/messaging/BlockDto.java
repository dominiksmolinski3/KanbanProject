package pl.myproject.kanbanproject2.messaging;

import java.time.Instant;

public record BlockDto(Integer userId, String name, Instant createdAt) {
}
