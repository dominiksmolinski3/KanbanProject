package pl.myproject.kanbanproject2.task;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * The new order of one cell, as the ids in the order they should read.
 *
 * <p>Position is the index in this list, which is why the ids are the whole request: sending
 * positions as well would let the two disagree, and there is no useful answer to a body that says
 * a task is both third and fifth.
 *
 * <p>Every id has to name a task in the same column and the same swimlane. A position is an ordinal
 * within one cell, so a list drawn from two of them would number two tasks 0 and leave nothing to
 * say which comes first.
 */
public record ReorderTasksRequest(
        @NotEmpty(message = "At least one task id is required") List<Integer> orderedIds
) {
}
