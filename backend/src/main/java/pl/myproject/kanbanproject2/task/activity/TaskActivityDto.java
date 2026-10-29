package pl.myproject.kanbanproject2.task.activity;

import java.time.Instant;

public record TaskActivityDto(
        Integer id,
        Integer taskId,
        String taskTitle,
        Integer actorId,
        String actorName,
        TaskActivityType type,
        String detail,
        Instant occurredAt) {
}
