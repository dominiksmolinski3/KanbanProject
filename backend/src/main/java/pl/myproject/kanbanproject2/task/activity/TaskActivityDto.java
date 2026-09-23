package pl.myproject.kanbanproject2.task.activity;

import java.time.LocalDateTime;

public record TaskActivityDto(
        Integer id,
        Integer taskId,
        String taskTitle,
        Integer actorId,
        String actorName,
        TaskActivityType type,
        String detail,
        LocalDateTime occurredAt) {
}
