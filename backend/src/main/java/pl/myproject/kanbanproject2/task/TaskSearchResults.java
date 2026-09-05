package pl.myproject.kanbanproject2.task;

import java.util.List;

/**
 * One page of search results, and enough about the rest of them to page through.
 *
 * <p>{@code totalTasks} is what makes this worth more than a bare list: a client cannot tell "the
 * last page" from "a page that happened to come back short" without it, and it is the number a
 * person wants to see beside a result list anyway. It costs a second, cheap {@code COUNT} over the
 * same filters.
 *
 * <p>There is no shared {@code dto/} package in this project, so this lives next to the feature it
 * describes - the same place {@link TaskDto} lives, for the same reason.
 *
 * <p><b>No {@code hasNext()}.</b> It was here, and running the route showed that Jackson serialises
 * a record by its components and nothing else - so the method was on the class, absent from every
 * response, and computed by the client from {@code page} and {@code totalPages} anyway. A helper
 * that reads as part of the API and never reaches the wire is worse than no helper.
 */
public record TaskSearchResults(List<TaskDto> tasks,
                                int page,
                                int size,
                                long totalTasks,
                                int totalPages) {
}
