package pl.myproject.kanbanproject2.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Set;

public record CreateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        String description,
        Integer position,
        LocalDateTime deadline,
        Set<String> labels,
        @Valid IdRef column,
        @Valid IdRef row) {
}
