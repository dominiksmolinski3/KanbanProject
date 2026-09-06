package pl.myproject.kanbanproject2.task.activity;

/**
 * The kinds of thing a feed entry can be.
 *
 * <p>Stored as a name rather than an ordinal, so inserting one in the middle later does not
 * rewrite the meaning of every row already written. The client turns it into a sentence through
 * {@code t()}, which is why nothing here is a phrase: a feed that stored English would be a
 * screen the other eight languages cannot translate.
 *
 * <p>{@code MOVED} carries the column name in {@code detail}; {@code ASSIGNED} and
 * {@code UNASSIGNED} carry the person's name. The rest carry nothing.
 */
public enum TaskActivityType {
    CREATED,
    MOVED,
    ASSIGNED,
    UNASSIGNED,
    COMPLETED,
    REOPENED,
    DELETED
}
