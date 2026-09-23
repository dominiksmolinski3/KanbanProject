package pl.myproject.kanbanproject2.task.comment;

import java.util.List;

/**
 * One page of a card's thread, shaped like {@code TaskActivityResults} and {@code ChatMessageResults}
 * for the same reasons: a total so a short page can be told from the last one, and no derived
 * helpers, since Jackson serialises a record by its components alone.
 */
public record TaskCommentResults(List<TaskCommentDto> comments,
                                 int page,
                                 int size,
                                 long totalEntries,
                                 int totalPages) {
}
