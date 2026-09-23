package pl.myproject.kanbanproject2.task;

import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.user.User;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class TaskMapper implements Function<Task, TaskDto> {

    @Override
    public TaskDto apply(Task task) {
        if (task == null) {
            return null;
        }

        Integer columnId = null;
        if (task.getColumn() != null) {
            columnId = task.getColumn().getId();
        }

        Integer rowId = null;
        if (task.getRow() != null) {
            rowId = task.getRow().getId();
        }

        Set<Integer> userIds = null;
        if (task.getUsers() != null) {
            userIds = task.getUsers().stream()
                    .map(User::getId)
                    .collect(Collectors.toSet());
        }

        Integer parentTaskId = null;
        if (task.getParentTask() != null) {
            parentTaskId = task.getParentTask().getId();
        }

        Set<Integer> childTaskIds = null;
        if (task.getChildTasks() != null && !task.getChildTasks().isEmpty()) {
            childTaskIds = task.getChildTasks().stream()
                    .map(Task::getId)
                    .collect(Collectors.toSet());
        }

        // Copied, not handed over: getLabels() returns Hibernate's own collection, and returning it
        // directly let it escape the transaction into Jackson with no session left to initialize
        // it (open-in-view=false), which made every GET /api/tasks answer 500.
        Set<String> labels = task.getLabels() == null ? null : new HashSet<>(task.getLabels());

        // @BatchSize on the collection makes this one query per fifty cards, not one per card.
        int openSubtasks = task.getSubTasks() == null ? 0 : (int) task.getSubTasks().stream()
                .filter(subTask -> !subTask.isCompleted())
                .count();

        return new TaskDto(
                task.getId(),
                task.getVersion(),
                task.getTitle(),
                task.getPosition(),
                columnId,
                rowId,
                userIds,
                labels,
                task.isCompleted(),
                task.getDescription(),
                parentTaskId,
                childTaskIds,
                task.getDeadline(),
                task.isExpired(),
                task.isDailyFocus(),
                openSubtasks
        );
    }
}