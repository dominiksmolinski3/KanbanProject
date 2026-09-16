package pl.myproject.kanbanproject2.task.activity;

import java.util.List;

/**
 * One page of the feed, shaped like {@code TaskSearchResults} for the same reasons: a total so a
 * short page can be told from the last one, and no derived helpers, since Jackson serialises a
 * record by its components alone.
 */
public record TaskActivityResults(List<TaskActivityDto> activities,
                                  int page,
                                  int size,
                                  long totalEntries,
                                  int totalPages) {
}
