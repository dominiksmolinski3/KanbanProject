package pl.myproject.kanbanproject2.task.subtask;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pl.myproject.kanbanproject2.task.CreateTaskRequest;
import pl.myproject.kanbanproject2.task.IdRef;

public record CreateSubTaskRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = CreateTaskRequest.DESCRIPTION_MAX_LENGTH) String description,
        boolean completed,
        Integer position,
        @NotNull @Valid IdRef task) {
}
