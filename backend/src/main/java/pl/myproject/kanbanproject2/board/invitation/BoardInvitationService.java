package pl.myproject.kanbanproject2.board.invitation;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardDto;
import pl.myproject.kanbanproject2.board.BoardMapper;
import pl.myproject.kanbanproject2.board.BoardRole;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.SupportedLocales;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.util.List;
import java.util.Locale;

@RequiredArgsConstructor
@Transactional
@Service
public class BoardInvitationService {

    private final BoardInvitationRepository invitationRepository;
    private final BoardInvitationMapper invitationMapper;
    private final BoardService boardService;
    private final BoardMapper boardMapper;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public BoardInvitationDto invite(User caller, Integer boardId, InviteRequest request) {
        Board board = boardService.requireOwned(caller, boardId);
        String email = BoardInvitation.normaliseEmail(request.email());

        if (board.everyone().stream().anyMatch(member -> email.equalsIgnoreCase(member.getEmail()))) {
            throw new GlobalException(ExceptionIdentifier.ALREADY_BOARD_MEMBER);
        }

        var existing = invitationRepository
                .findByBoardAndEmailAndStatus(board, email, InvitationStatus.PENDING);
        if (existing.isPresent()) {
            return invitationMapper.apply(existing.get());
        }

        var role = request.role() == null ? BoardRole.MEMBER : request.role();
        var invitation = invitationRepository.save(new BoardInvitation(board, email, caller, role));
        announce(invitation, caller);
        return invitationMapper.apply(invitation);
    }

    public List<BoardInvitationDto> pendingFor(User caller, Integer boardId) {
        Board board = boardService.requireOwned(caller, boardId);
        return invitationRepository
                .findByBoardAndStatusOrderByIdAsc(board, InvitationStatus.PENDING)
                .stream()
                .map(invitationMapper)
                .toList();
    }

    public void revoke(User caller, Integer boardId, Integer invitationId) {
        Board board = boardService.requireOwned(caller, boardId);
        var invitation = invitationRepository.findById(invitationId)
                .filter(row -> row.getBoard() != null
                        && row.getBoard().getId().equals(board.getId()))
                .filter(row -> row.getStatus() == InvitationStatus.PENDING)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVITATION_NOT_FOUND));

        invitation.resolveAs(InvitationStatus.REVOKED);
        invitationRepository.save(invitation);
    }

    public List<BoardInvitationDto> myInvitations(User caller) {
        return pendingRowsFor(caller).stream().map(invitationMapper).toList();
    }

    public BoardDto accept(User caller, Integer invitationId) {
        var invitation = mineOrNotFound(caller, invitationId);
        invitation.resolveAs(InvitationStatus.ACCEPTED);
        invitationRepository.save(invitation);
        return boardMapper.apply(
                boardService.addAcceptedMember(invitation.getBoard(), caller, invitation.getRole()),
                caller);
    }

    public void decline(User caller, Integer invitationId) {
        var invitation = mineOrNotFound(caller, invitationId);
        invitation.resolveAs(InvitationStatus.DECLINED);
        invitationRepository.save(invitation);
    }

    private List<BoardInvitation> pendingRowsFor(User caller) {
        String email = BoardInvitation.normaliseEmail(caller == null ? null : caller.getEmail());
        if (email == null || email.isBlank()) {
            return List.of();
        }
        return invitationRepository.findByEmailAndStatusOrderByIdAsc(email, InvitationStatus.PENDING);
    }

    private BoardInvitation mineOrNotFound(User caller, Integer invitationId) {
        String email = BoardInvitation.normaliseEmail(caller == null ? null : caller.getEmail());
        return invitationRepository.findById(invitationId)
                .filter(row -> row.getStatus() == InvitationStatus.PENDING)
                .filter(row -> row.getEmail() != null && row.getEmail().equals(email))
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVITATION_NOT_FOUND));
    }

    private void announce(BoardInvitation invitation, User inviter) {
        var account = userRepository.findByEmail(invitation.getEmail());
        Locale locale = SupportedLocales.toLocale(
                account.map(User::getLocale).orElseGet(inviter::getLocale));

        emailService.sendBoardInvitation(
                invitation.getEmail(),
                invitation.getBoard().getName(),
                inviter.getName(),
                account.isPresent(),
                locale);
    }
}
