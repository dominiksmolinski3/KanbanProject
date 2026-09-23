package pl.myproject.kanbanproject2.board;

import pl.myproject.kanbanproject2.task.Task;

import java.util.List;

public record BoardTasksDeleting(Board board, List<Task> tasks) {
}
