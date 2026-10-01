package pl.myproject.kanbanproject2.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.Set;

public record CreateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = CreateTaskRequest.DESCRIPTION_MAX_LENGTH) String description,
        Integer position,
        LocalDateTime deadline,
        Set<@Size(max = CreateTaskRequest.LABEL_MAX_LENGTH) String> labels,
        @Valid IdRef column,
        @Valid IdRef row) {
    public static final int DESCRIPTION_MAX_LENGTH = 10_000;
    public static final int LABEL_MAX_LENGTH = 255;
}
