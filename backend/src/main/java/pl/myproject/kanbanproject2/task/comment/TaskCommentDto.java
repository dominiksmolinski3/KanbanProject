package pl.myproject.kanbanproject2.task.comment;

import java.time.Instant;

/**
 * What the panel needs to draw one comment. {@code authorId} is what the client compares against
 * the signed-in account to decide whether to offer edit and delete; the server makes the same
 * comparison again on the way in, so the client's answer is only ever a courtesy.
 */
public record TaskCommentDto(Long id,
                             Integer taskId,
                             String body,
                             Integer authorId,
                             String authorName,
                             Instant createdAt,
                             Instant editedAt) {
}
