package pl.myproject.kanbanproject2.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.LocalDateTime;
import java.util.Set;

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

    private static <T> JsonNullable<T> undefinedIfNull(JsonNullable<T> value) {
        return value == null ? JsonNullable.undefined() : value;
    }
}
