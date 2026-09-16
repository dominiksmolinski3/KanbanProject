package pl.myproject.kanbanproject2.board.invitation;

import java.time.LocalDateTime;

/**
 * An invitation as either side sees it — one record safe for both listings. Deliberately
 * <b>not</b> here: anything about the account behind the address (id, name, "has signed up"), since
 * disclosing that is exactly what invitations replaced {@code POST /boards/{id}/members} to avoid.
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
