package pl.myproject.kanbanproject2.task.activity;

import java.time.LocalDateTime;

/**
 * One entry, as the client renders it.
 *
 * <p>{@code taskId} is nullable and {@code taskTitle} is not: the task may be gone, and the entry
 * saying so is the one that most needs to still read. The client uses the id only to decide
 * whether the title is a link.
 *
 * <p>Nothing here is a sentence. {@code type} is the enum name and the client turns it into
 * wording through {@code t()}, because a feed that stored English would be a screen the other
 * eight languages cannot translate.
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
