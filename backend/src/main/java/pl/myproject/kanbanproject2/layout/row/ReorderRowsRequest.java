package pl.myproject.kanbanproject2.layout.row;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReorderRowsRequest(
        @NotEmpty(message = "At least one swimlane id is required") List<Integer> orderedIds
) {
}
