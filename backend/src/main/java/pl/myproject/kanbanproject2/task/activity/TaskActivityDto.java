package pl.myproject.kanbanproject2.task.activity;

import java.time.LocalDateTime;

/**
 * One entry, as the client renders it. {@code taskId} is nullable while {@code taskTitle} is not,
 * since the task may be gone and the entry saying so still needs to read. {@code type} is the enum
 * name, turned into wording through {@code t()} so the feed stays translatable.
 */
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
