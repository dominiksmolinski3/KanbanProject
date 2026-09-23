package pl.myproject.kanbanproject2.task.flow;

/** What a board now defines as start and done (FLOW-02); a null end is the default. */
public record FlowDefinitionDto(Integer boardId, Integer startColumnId, Integer doneColumnId) {
}
