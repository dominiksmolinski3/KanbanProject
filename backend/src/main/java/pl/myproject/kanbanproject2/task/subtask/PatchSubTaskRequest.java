package pl.myproject.kanbanproject2.task.subtask;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;
import pl.myproject.kanbanproject2.task.CreateTaskRequest;
import pl.myproject.kanbanproject2.task.IdRef;

public record PatchSubTaskRequest(
        JsonNullable<@Size(max = 255) String> title,
        JsonNullable<@Size(max = CreateTaskRequest.DESCRIPTION_MAX_LENGTH) String> description,
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
