package pl.myproject.kanbanproject2.layout.column;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateColumnRequest(
        @NotBlank @Size(max = 255) String name,
        Integer position,
        @PositiveOrZero Integer wipLimit) {
}
