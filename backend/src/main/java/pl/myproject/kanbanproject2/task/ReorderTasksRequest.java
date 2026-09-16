package pl.myproject.kanbanproject2.task;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * The new order of one cell, as the ids in the order they should read. Position is the index in
 * this list — sending positions too would let the two disagree — and every id must name a task in
 * the same column and swimlane, since a position is an ordinal within one cell.
 */
public record ReorderTasksRequest(
        @NotEmpty(message = "At least one task id is required") List<Integer> orderedIds
) {
}
