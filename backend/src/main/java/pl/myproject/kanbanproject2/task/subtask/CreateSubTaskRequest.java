package pl.myproject.kanbanproject2.task.subtask;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import pl.myproject.kanbanproject2.task.IdRef;

/**
 * The writable half of a new subtask. The parent task is resolved from the repository rather than
 * taken as a detached entity, so an unknown id is a 404 rather than a constraint violation at flush
 * time. The task is required — it used to be optional, which produced a subtask attached to
 * nothing and, since a subtask's board is read through its task, unreachable by any board.
 */
public record CreateSubTaskRequest(
        @NotBlank String title,
        String description,
        boolean completed,
        Integer position,
        @NotNull @Valid IdRef task) {
}
