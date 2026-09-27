package pl.myproject.kanbanproject2.layout.row;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record PatchRowRequest(
        @Size(min = 1, max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        Integer position,
        @PositiveOrZero Integer wipLimit) {
}
