package pl.myproject.kanbanproject2.board.invitation;

/**
 * What has become of an invitation.
 *
 * <p>Four states rather than a boolean, because the three ways one ends are not the same fact and
 * the board's owner is entitled to tell them apart: {@code ACCEPTED} put somebody on the board,
 * {@code DECLINED} was the invitee's answer, and {@code REVOKED} was the owner's own second
 * thought. Only {@code PENDING} is actionable, and only {@code PENDING} rows are unique per
 * (board, address) - see {@code V14}.
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    REVOKED
}
