package pl.myproject.kanbanproject2.board;

/**
 * How much a member of a board may do to it, beyond seeing it.
 *
 * <p>Deliberately not a third level of ownership - {@link Board#isOwnedBy} is unrelated and stays a
 * separate check against {@code boards.owner_id}. This is the split the audit called FEAT-08: the
 * commonest access level the two-level model left out was a stakeholder who should see a board and
 * not move anything on it. {@code MEMBER} is the write access every row in {@code board_members}
 * already had before this column existed (see {@code V21}), so it is the default rather than
 * {@code VIEWER}.
 */
public enum BoardRole {
    MEMBER,
    VIEWER
}
