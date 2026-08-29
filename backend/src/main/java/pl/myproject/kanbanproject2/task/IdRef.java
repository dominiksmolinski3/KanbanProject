package pl.myproject.kanbanproject2.task;

import jakarta.validation.constraints.NotNull;

/**
 * A reference to an existing record, as the client sends it: {@code {"id": 4}}.
 *
 * <p>The board's request bodies nest their associations this way — {@code column}, {@code row},
 * {@code task} — so the shape is kept even though only the id is ever read. The service resolves it
 * against the repository, which is what turns an unknown id into a 404 instead of a foreign-key
 * violation at flush time.
 */
public record IdRef(@NotNull Integer id) {
}
