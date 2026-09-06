package pl.myproject.kanbanproject2.task.activity;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.user.User;

/**
 * The write side of the feed, and the only thing that writes it.
 *
 * <p>Separate from {@link TaskActivityService}, which only reads, because the two have opposite
 * dependents: {@code TaskService} writes and never reads, the controller reads and never writes,
 * and a single class doing both would give each of them a handle on the other half. It is also
 * what keeps the write calls short enough to sit beside the mutation they describe without
 * burying it.
 *
 * <p><b>Every method is a no-op for a task with no board.</b> Nothing in this application produces
 * one - {@code board_id} is not null and {@code TaskService} sets it before the first save - but a
 * feed entry is a side effect of somebody else's operation, and a side effect that can throw turns
 * a recording failure into a failed edit. That is the wrong trade in the one direction that
 * matters.
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
     * Written before the task is removed, and the entry keeps its title rather than a reference.
     *
     * <p>The caller also has to detach the entries that already point at this task - see
     * {@link #detachFrom} - because there is no cascade on that column and a delete would
     * otherwise fail on the foreign key.
     */
    public void deleted(User actor, Task task) {
        record(actor, task, TaskActivityType.DELETED, null);
    }

    /**
     * Takes the task off its own entries so the row can go.
     *
     * <p>The alternative is {@code on delete set null} in the schema, which does the same thing
     * further from where it can be seen. This project already does the same by hand for attachment
     * blobs and history rows, for the same reason: the code that deletes a thing should be the
     * code that says what happens to what pointed at it.
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
