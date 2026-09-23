package pl.myproject.kanbanproject2.board;

import pl.myproject.kanbanproject2.task.Task;

import java.util.List;

/**
 * Published by {@link BoardService#deleteBoard} just before it removes a board's tasks, inside the
 * same transaction, so anything hanging rows off a task can take them first.
 *
 * <p>An event rather than a call because the call would be a cycle: {@code BoardService} is the one
 * service every feature depends on for its access checks, so it cannot depend on those features back.
 * It used to spell every table out by hand for that reason, and the list went stale the first time a
 * feature forgot to add itself - {@code task_attachments} never did, so any board with a file on a
 * card could not be deleted at all. A listener sits in the feature that owns the rows, beside the
 * {@code deleteAllFor(Task)} that {@code TaskService.deleteTask} already calls, which is where
 * somebody adding a table is already looking.
 *
 * <p><b>Listeners must be synchronous and must not be {@code @TransactionalEventListener}.</b> The
 * rows have to be gone before the tasks' {@code DELETE} is flushed, in the same transaction; a
 * listener that ran after commit would find the foreign key already refusing.
 */
public record BoardTasksDeleting(Board board, List<Task> tasks) {
}
