package pl.myproject.kanbanproject2.board.invitation;

import java.time.LocalDateTime;

/**
 * An invitation as either side sees it.
 *
 * <p>One record for both listings, and the fields are chosen so that it is safe in both. The
 * owner's listing needs the address and when it was sent; the invitee's needs the board's name and
 * who sent it, because "you have been invited to a board" with neither is not an offer anybody can
 * act on. What is deliberately <b>not</b> here is anything about the account behind the address -
 * no id, no name, no "this person has signed up" flag. The whole reason invitations replaced
 * {@code POST /boards/{id}/members} is that its response let an owner tell those apart.
 */
public record BoardInvitationDto(
        Integer id,
        Integer boardId,
        String boardName,
        String email,
        String invitedByName,
        InvitationStatus status,
        LocalDateTime createdAt) {
}
