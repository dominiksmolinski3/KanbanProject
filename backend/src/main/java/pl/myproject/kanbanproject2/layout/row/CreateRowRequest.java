package pl.myproject.kanbanproject2.layout.row;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The writable half of a new swimlane — see {@link pl.myproject.kanbanproject2.layout.column.CreateColumnRequest}
 * for why {@link Row} is no longer bound straight off the wire.
 */
public record CreateRowRequest(
        @NotBlank @Size(max = 255) String name,
        Integer position,
        @PositiveOrZero Integer wipLimit) {
}
