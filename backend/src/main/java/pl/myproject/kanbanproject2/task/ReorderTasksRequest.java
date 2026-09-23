package pl.myproject.kanbanproject2.task;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReorderTasksRequest(
        @NotEmpty(message = "At least one task id is required") List<Integer> orderedIds
) {
}
