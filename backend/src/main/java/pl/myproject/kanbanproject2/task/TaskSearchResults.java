package pl.myproject.kanbanproject2.task;

import java.util.List;

/**
 * One page of search results, and enough about the rest of them to page through.
 * {@code totalTasks} is what lets a client tell the last page from one that came back short, at
 * the cost of a second, cheap {@code COUNT} over the same filters. There is no {@code hasNext()}:
 * Jackson serialises a record by its components alone, so a derived helper would sit on the class
 * without ever reaching the wire.
 */
public record TaskSearchResults(List<TaskDto> tasks,
                                int page,
                                int size,
                                long totalTasks,
                                int totalPages) {
}
