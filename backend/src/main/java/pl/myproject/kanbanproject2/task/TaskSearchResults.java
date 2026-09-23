package pl.myproject.kanbanproject2.task;

import java.util.List;

public record TaskSearchResults(List<TaskDto> tasks,
                                int page,
                                int size,
                                long totalTasks,
                                int totalPages) {
}
