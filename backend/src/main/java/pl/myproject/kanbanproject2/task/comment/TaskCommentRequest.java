package pl.myproject.kanbanproject2.task.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The one writable field of a comment, used for writing a new one and for rewriting an existing one.
 * The limit is chat's, and the column's: a comment longer than a chat message is a document, and an
 * attachment is the place for one.
 */
public record TaskCommentRequest(@NotBlank @Size(max = TaskCommentRequest.MAX_LENGTH) String body) {

    public static final int MAX_LENGTH = 2000;
}
