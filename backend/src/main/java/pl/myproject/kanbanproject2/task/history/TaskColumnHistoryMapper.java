package pl.myproject.kanbanproject2.task.history;

import org.springframework.stereotype.Component;

@Component
public class TaskColumnHistoryMapper {
    public TaskColumnHistoryDto toDTO(TaskColumnHistory history) {
        TaskColumnHistoryDto dto = new TaskColumnHistoryDto();
        dto.setId(history.getId());
        dto.setTaskId(history.getTask().getId());
        dto.setTaskTitle(history.getTask().getTitle());
        // Null once the column has been deleted: V17 detaches these rows rather than deleting
        // them, and dereferencing it here turned every such task's history panel into a 500.
        dto.setColumnId(history.getColumn() != null ? history.getColumn().getId() : null);
        dto.setColumnName(history.getColumnName());
        dto.setChangedAt(history.getChangedAt());
        return dto;
    }
}