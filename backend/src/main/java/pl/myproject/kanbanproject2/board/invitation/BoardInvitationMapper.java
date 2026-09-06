package pl.myproject.kanbanproject2.board.invitation;

import org.springframework.stereotype.Component;

import java.util.function.Function;

/** The usual shape: a {@code @Component} implementing {@code Function}, called as a method ref. */
@Component
public class BoardInvitationMapper implements Function<BoardInvitation, BoardInvitationDto> {

    @Override
    public BoardInvitationDto apply(BoardInvitation invitation) {
        if (invitation == null) {
            return null;
        }
        var board = invitation.getBoard();
        var invitedBy = invitation.getInvitedBy();
        return new BoardInvitationDto(
                invitation.getId(),
                board == null ? null : board.getId(),
                board == null ? null : board.getName(),
                invitation.getEmail(),
                invitedBy == null ? null : invitedBy.getName(),
                invitation.getStatus(),
                invitation.getCreatedAt());
    }
}
