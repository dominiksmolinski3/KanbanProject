package pl.myproject.kanbanproject2.task.activity;

import java.util.List;

/**
 * One page of the feed, shaped exactly like {@code TaskSearchResults} and for the same reasons -
 * a total, so a short page can be told from the last one, and no derived helpers, because Jackson
 * serialises a record by its components and a method here would read as part of the API and never
 * reach the wire.
 */
public record TaskActivityResults(List<TaskActivityDto> activities,
                                  int page,
                                  int size,
                                  long totalEntries,
                                  int totalPages) {
}
