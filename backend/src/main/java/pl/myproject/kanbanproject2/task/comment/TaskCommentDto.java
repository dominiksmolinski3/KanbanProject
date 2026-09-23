package pl.myproject.kanbanproject2.task.comment;

import java.time.Instant;

public record TaskCommentDto(Long id,
                             Integer taskId,
                             String body,
                             Integer authorId,
                             String authorName,
                             Instant createdAt,
                             Instant editedAt) {
}
