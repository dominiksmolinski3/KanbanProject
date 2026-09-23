package pl.myproject.kanbanproject2.board.event;

/**
 * What part of a board changed, which is the whole of what goes on the wire.
 *
 * <p>Four values rather than one per mutation,because the receiver does not act on the mutation
 * - it re-reads. Each value names the read that answers it: {@code TASKS} is
 * {@code refreshTasks()}, and the two layout kinds are {@code refreshBoard()}, which re-reads the
 * tasks as well because deleting a column takes its cards with it.
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
    COMMENTS
}
