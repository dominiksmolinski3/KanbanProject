package pl.myproject.kanbanproject2.task.comment;

import java.util.List;

public record TaskCommentResults(List<TaskCommentDto> comments,
                                 int page,
                                 int size,
                                 long totalEntries,
                                 int totalPages) {
}
