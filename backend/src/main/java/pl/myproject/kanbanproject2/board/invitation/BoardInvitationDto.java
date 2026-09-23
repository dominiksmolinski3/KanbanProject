package pl.myproject.kanbanproject2.board.invitation;

import pl.myproject.kanbanproject2.board.BoardRole;

import java.time.LocalDateTime;

public record BoardInvitationDto(
        Integer id,
        Integer boardId,
        String boardName,
        String email,
        String invitedByName,
        InvitationStatus status,
        BoardRole role,
        LocalDateTime createdAt) {
}
