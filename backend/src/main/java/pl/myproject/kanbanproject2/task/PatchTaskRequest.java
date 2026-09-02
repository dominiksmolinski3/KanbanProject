package pl.myproject.kanbanproject2.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * A partial update of a task, where "not sent" and "sent as null" are different requests.
 *
 * <p>{@link JsonNullable#isPresent()} answers the first question and the wrapped value answers the
 * second, so {@code {"row": null}} detaches the task from its swimlane instead of being read as
 * "leave the swimlane alone" — the read the frontend's row-delete path had always assumed and never
 * got. Fields absent from the body are left untouched, as before.
 *
 * <p>{@code completed} is not here on purpose: completion goes through
 * {@code PATCH /tasks/{id}/complete/{status}}, the one route that enforces the parent-task rule.
 *
 * <p>{@code version} is the one plain field rather than a {@link JsonNullable}: for the others,
 * "sent as null" and "left out" are different requests, and for the version they are the same one -
 * no version means "do not check", so absent deserialising to {@code null} is exactly right. When it
 * is present, {@code TaskService} refuses the write if the task has moved on since the caller last
 * read it.
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
