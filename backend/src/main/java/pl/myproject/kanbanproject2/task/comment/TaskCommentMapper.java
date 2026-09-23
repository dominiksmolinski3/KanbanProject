package pl.myproject.kanbanproject2.task.comment;

import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
public class TaskCommentMapper implements Function<TaskComment, TaskCommentDto> {

    @Override
    public TaskCommentDto apply(TaskComment comment) {
        if (comment == null) {
            return null;
        }
        var author = comment.getAuthor();
        return new TaskCommentDto(
                comment.getId(),
                comment.getTask() != null ? comment.getTask().getId() : null,
                comment.getBody(),
                author != null ? author.getId() : null,
                author != null ? author.getName() : null,
                comment.getCreatedAt(),
                comment.getEditedAt());
    }
}
