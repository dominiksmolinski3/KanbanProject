package pl.myproject.kanbanproject2.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * The writable half of a new task.
 *
 * <p>Deliberately narrower than {@link Task}: no {@code id} (which would turn the insert into a
 * merge over whatever record already holds that key), no {@code completed} (that route is
 * {@code PATCH /tasks/{id}/complete/{status}}, which enforces the parent rule), no {@code users},
 * {@code subTasks}, {@code parentTask} or {@code childTasks} — each of those has its own endpoint
 * and its own checks.
 */
public record CreateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        String description,
        Integer position,
        LocalDateTime deadline,
        Set<String> labels,
        @Valid IdRef column,
        @Valid IdRef row) {
}
