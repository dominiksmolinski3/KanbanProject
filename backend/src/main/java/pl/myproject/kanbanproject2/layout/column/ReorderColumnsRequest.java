package pl.myproject.kanbanproject2.layout.column;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReorderColumnsRequest(
        @NotEmpty(message = "At least one column id is required") List<Integer> orderedIds
) {
}
