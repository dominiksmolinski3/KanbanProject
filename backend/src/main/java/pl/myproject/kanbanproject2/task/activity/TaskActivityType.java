package pl.myproject.kanbanproject2.task.activity;

/**
 * The kinds of thing a feed entry can be, stored as a name rather than an ordinal so inserting one
 * later doesn't rewrite existing rows' meaning. The client turns it into a sentence through
 * {@code t()}; {@code MOVED} carries the column name in {@code detail}, {@code ASSIGNED}/
 * {@code UNASSIGNED} carry the person's name, and the rest carry nothing.
 */
public enum TaskActivityType {
    CREATED,
    MOVED,
    ASSIGNED,
    UNASSIGNED,
    COMPLETED,
    REOPENED,
    DELETED,
    /** Somebody commented on the card. The words stay in the thread; the feed only says it happened. */
    COMMENTED
}
