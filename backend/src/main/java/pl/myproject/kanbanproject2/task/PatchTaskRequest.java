package pl.myproject.kanbanproject2.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * A partial update of a task, where "not sent" and "sent as null" are different requests via
 * {@link JsonNullable#isPresent()} — {@code {"row": null}} detaches the swimlane rather than being
 * read as "leave it alone". {@code completed} is deliberately absent since completion goes through
 * {@code PATCH /tasks/{id}/complete/{status}}, the route that enforces the parent-task rule.
 * {@code version} is a plain field rather than a {@link JsonNullable} because for it, absent and
 * null mean the same thing — "do not check" — and when present, {@code TaskService} refuses the
 * write if the task has moved on since the caller last read it.
 */
public record PatchTaskRequest(
        JsonNullable<@Size(max = 255) String> title,
        JsonNullable<String> description,
        JsonNullable<Integer> position,
        JsonNullable<LocalDateTime> deadline,
        JsonNullable<Set<String>> labels,
        JsonNullable<@Valid IdRef> column,
        JsonNullable<@Valid IdRef> row,
        Integer version) {

    public PatchTaskRequest {
        title = undefinedIfNull(title);
        description = undefinedIfNull(description);
        position = undefinedIfNull(position);
        deadline = undefinedIfNull(deadline);
        labels = undefinedIfNull(labels);
        column = undefinedIfNull(column);
        row = undefinedIfNull(row);
    }

    /**
     * Jackson hands a record constructor a bare {@code null} for a property it never saw, so
     * normalise that to {@code undefined()} and let every caller read the wrapper without a
     * null check.
     */
    private static <T> JsonNullable<T> undefinedIfNull(JsonNullable<T> value) {
        return value == null ? JsonNullable.undefined() : value;
    }
}
