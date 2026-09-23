package pl.myproject.kanbanproject2.board.event;

public record BoardEvent(BoardEventType type, Integer boardId) {
}
