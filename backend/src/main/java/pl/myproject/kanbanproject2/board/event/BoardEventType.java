package pl.myproject.kanbanproject2.board.event;

/**
 * What part of a board changed, which is the whole of what goes on the wire.
 *
 * <p>A handful of values rather than one per mutation, because the receiver does not act on the
 * mutation - it re-reads. Each value names the read that answers it: {@code TASKS} is
 * {@code refreshTasks()}, the two layout kinds are {@code refreshBoard()}, which re-reads the
 * tasks as well because deleting a column takes its cards with it, and the rest name a part of the
 * open task panel.
 */
public enum BoardEventType {

    /** A task was created, edited, moved, assigned, completed, repositioned or deleted. */
    TASKS,

    /** A column was created, renamed, re-limited, reordered or deleted. */
    COLUMNS,

    /** A swimlane was created, renamed, re-limited, reordered or deleted. */
    ROWS,

    /**
     * Somebody commented on a card, or edited or removed a comment. Its own kind rather than
     * {@code TASKS}, because nothing on the board itself changes - the read it names is the open
     * task panel's thread, and a board re-read for it would be every card for one sentence.
     */
    COMMENTS,

    /**
     * A subtask was added, ticked, edited, moved or removed. The card itself changed - it carries
     * its open-subtask count, which is what drives the "unfinished subtasks" warning - so this is
     * a {@code refreshTasks()} like {@code TASKS}, and the open task panel re-reads its own list as
     * well, which no board read reaches.
     */
    SUBTASKS,

    /**
     * A file was attached to a card or removed from one. Like {@code COMMENTS} it changes nothing
     * the board draws, so it names only the open task panel's attachment list.
     */
    ATTACHMENTS
}
