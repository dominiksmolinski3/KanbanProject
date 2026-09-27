package pl.myproject.kanbanproject2.user;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record PatchUserRequest(
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        @Pattern(regexp = ".*\\S.*", message = "Username must not be blank")
        String name,

        @PositiveOrZero(message = "WIP limit must not be negative")
        Integer wipLimit,

        @Size(max = 35, message = "Locale must not exceed 35 characters")
        String locale) {
}
