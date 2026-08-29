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

        /*
         * Copied, not handed over. getLabels() returns Hibernate's own collection, and putting it
         * straight into the DTO carried it out of the service's transaction and into Jackson -
         * which then tried to initialize it during serialisation, with open-in-view=false and no
         * session left to do it with. Every GET /api/tasks answered 500.
         *
         * Copying inside the transaction both initializes it where a session still exists and
         * stops a persistent collection escaping into a response at all, which is what a DTO is
         * for. The other two are already copies: Collectors.toSet() builds a plain set.
         */
        Set<String> labels = task.getLabels() == null ? null : new HashSet<>(task.getLabels());

        return new TaskDto(
                task.getId(),
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
                task.isDailyFocus()
        );
    }
}