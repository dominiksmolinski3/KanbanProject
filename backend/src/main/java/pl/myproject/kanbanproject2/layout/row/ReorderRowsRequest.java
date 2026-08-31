package pl.myproject.kanbanproject2.layout.row;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * The new top-to-bottom order of a board's swimlanes, as ids. Position is the index in this list.
 *
 * <p>Every id has to name a swimlane on the same board; see {@code ReorderColumnsRequest}.
 */
public record ReorderRowsRequest(
        @NotEmpty(message = "At least one swimlane id is required") List<Integer> orderedIds
) {
}
