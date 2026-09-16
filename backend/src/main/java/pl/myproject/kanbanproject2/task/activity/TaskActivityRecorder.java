package pl.myproject.kanbanproject2.task.activity;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.user.User;

/**
 * The write side of the feed, and the only thing that writes it — separate from
 * {@link TaskActivityService}, which only reads, so neither class needs a handle on the other's
 * half. Every method is a no-op for a task with no board: a feed entry is a side effect of
 * somebody else's operation, and a side effect that can throw would turn a recording failure into
 * a failed edit.
 */
@RequiredArgsConstructor
@Component
public class TaskActivityRecorder {

    private final TaskActivityRepository repository;

    public void created(User actor, Task task) {
        record(actor, task, TaskActivityType.CREATED, null);
    }

    /** The column name is copied, not referenced: a column renamed later did not have that name. */
    public void moved(User actor, Task task, String columnName) {
        record(actor, task, TaskActivityType.MOVED, columnName);
    }

    public void assigned(User actor, Task task, User assignee) {
        record(actor, task, TaskActivityType.ASSIGNED, nameOf(assignee));
    }

    public void unassigned(User actor, Task task, User assignee) {
        record(actor, task, TaskActivityType.UNASSIGNED, nameOf(assignee));
    }

    public void completionChanged(User actor, Task task, boolean completed) {
        record(actor, task,
                completed ? TaskActivityType.COMPLETED : TaskActivityType.REOPENED, null);
    }

    /**
     * Written before the task is removed, keeping its title rather than a reference. The caller
     * must also call {@link #detachFrom} to detach entries already pointing at this task, since
     * there is no cascade on that column.
     */
    public void deleted(User actor, Task task) {
        record(actor, task, TaskActivityType.DELETED, null);
    }

    /**
     * Takes the task off its own entries so the row can go — the same by-hand pattern this project
     * uses for attachment blobs and history rows, rather than an {@code on delete set null} buried
     * in the schema.
     */
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
