package pl.myproject.kanbanproject2.layout.column;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * The new left-to-right order of a board's stages, as ids. Position is the index in this list.
 *
 * <p>Every id has to name a column on the same board, for the same reason a task reorder has to
 * stay inside one cell: two boards number their stages independently.
 */
public record ReorderColumnsRequest(
        @NotEmpty(message = "At least one column id is required") List<Integer> orderedIds
) {
}
