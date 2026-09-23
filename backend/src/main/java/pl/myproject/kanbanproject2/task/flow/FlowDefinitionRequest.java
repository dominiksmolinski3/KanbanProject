package pl.myproject.kanbanproject2.task.flow;

/**
 * The board's definition of when work starts and when it is done, as column ids. Either may be null,
 * which puts that end back on the default: the last column for done, arrival on the board for start.
 */
public record FlowDefinitionRequest(Integer startColumnId, Integer doneColumnId) {
}
