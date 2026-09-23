package pl.myproject.kanbanproject2.task.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskCommentRequest(@NotBlank @Size(max = TaskCommentRequest.MAX_LENGTH) String body) {
    public static final int MAX_LENGTH = 2000;
}
