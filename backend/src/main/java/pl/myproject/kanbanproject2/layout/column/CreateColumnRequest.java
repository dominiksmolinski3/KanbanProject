package pl.myproject.kanbanproject2.layout.column;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The writable half of a new column.
 *
 * <p>{@link Column} used to be bound here directly, which let a create request carry an {@code id}
 * — and an id turns {@code save} into a merge over whatever column already holds that key — or a
 * {@code tasks} list, which cascades ALL and would have persisted tasks through the back door.
 */
public record CreateColumnRequest(
        @NotBlank @Size(max = 255) String name,
        Integer position,
        @PositiveOrZero Integer wipLimit) {
}
