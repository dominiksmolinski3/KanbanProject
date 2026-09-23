package pl.myproject.kanbanproject2.task.activity;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.user.User;

@RequiredArgsConstructor
@Component
public class TaskActivityRecorder {

    private final TaskActivityRepository repository;

    public void created(User actor, Task task) {
        record(actor, task, TaskActivityType.CREATED, null);
    }

    public void moved(User actor, Task task, String columnName) {
        record(actor, task, TaskActivityType.MOVED, columnName);
    }

    public void assigned(User actor, Task task, User assignee) {
        record(actor, task, TaskActivityType.ASSIGNED, nameOf(assignee));
    }

    public void unassigned(User actor, Task task, User assignee) {
        record(actor, task, TaskActivityType.UNASSIGNED, nameOf(assignee));
    }

    public void commented(User actor, Task task) {
        record(actor, task, TaskActivityType.COMMENTED, null);
    }

    public void completionChanged(User actor, Task task, boolean completed) {
        record(actor, task,
                completed ? TaskActivityType.COMPLETED : TaskActivityType.REOPENED, null);
    }

    public void deleted(User actor, Task task) {
        record(actor, task, TaskActivityType.DELETED, null);
    }

    public void detachFrom(Task task) {
        var entries = repository.findByTask(task);
        entries.forEach(entry -> entry.setTask(null));
        repository.saveAll(entries);
    }

    private void record(User actor, Task task, TaskActivityType type, String detail) {
        if (task == null || task.getBoard() == null) {
            return;
        }
        repository.save(new TaskActivity(task.getBoard(), task, actor, type, detail));
    }

    private static String nameOf(User user) {
        return user == null ? null : user.getName();
    }
}
