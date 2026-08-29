package pl.myproject.kanbanproject2.task.subtask;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import pl.myproject.kanbanproject2.task.IdRef;

/**
 * The writable half of a new subtask.
 *
 * <p>{@code completed} is accepted because the client sends it on create, but the parent task is
 * resolved from the repository rather than taken as a detached entity — an unknown id is a 404 now,
 * not a constraint violation at flush time.
 */
public record CreateSubTaskRequest(
        @NotBlank String title,
        String description,
        boolean completed,
        Integer position,
        @Valid IdRef task) {
}
