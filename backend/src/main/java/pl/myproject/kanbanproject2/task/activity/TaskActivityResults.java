package pl.myproject.kanbanproject2.task.activity;

import java.util.List;

public record TaskActivityResults(List<TaskActivityDto> activities,
                                  int page,
                                  int size,
                                  long totalEntries,
                                  int totalPages) {
}
