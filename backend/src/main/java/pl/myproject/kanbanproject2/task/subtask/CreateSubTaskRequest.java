package pl.myproject.kanbanproject2.task.subtask;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import pl.myproject.kanbanproject2.task.IdRef;

public record CreateSubTaskRequest(
        @NotBlank String title,
        String description,
        boolean completed,
        Integer position,
        @NotNull @Valid IdRef task) {
}
