package pl.myproject.kanbanproject2.layout.row;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateRowRequest(
        @NotBlank @Size(max = 255) String name,
        Integer position,
        @PositiveOrZero Integer wipLimit) {
}
