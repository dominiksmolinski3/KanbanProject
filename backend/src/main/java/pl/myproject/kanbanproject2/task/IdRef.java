package pl.myproject.kanbanproject2.task;

import jakarta.validation.constraints.NotNull;

public record IdRef(@NotNull Integer id) {
}
