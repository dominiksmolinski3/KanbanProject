package pl.myproject.kanbanproject2.task.subtask;

import jakarta.validation.Valid;
import org.openapitools.jackson.nullable.JsonNullable;
import pl.myproject.kanbanproject2.task.IdRef;

public record PatchSubTaskRequest(
        JsonNullable<String> title,
        JsonNullable<String> description,
        JsonNullable<Boolean> completed,
        JsonNullable<Integer> position,
        JsonNullable<@Valid IdRef> task) {

    public PatchSubTaskRequest {
        title = undefinedIfNull(title);
        description = undefinedIfNull(description);
        completed = undefinedIfNull(completed);
        position = undefinedIfNull(position);
        task = undefinedIfNull(task);
    }

    private static <T> JsonNullable<T> undefinedIfNull(JsonNullable<T> value) {
        return value == null ? JsonNullable.undefined() : value;
    }
}
