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

/**
 * Invitations: an owner offers membership, and the person offered decides. This replaces
 * {@code BoardService.addMember}, which put an account on a board immediately and answered with the
 * member list — letting an owner diff it to learn whether an address had an account here. The
 * response to an invite is the invitation and never the board.
 *
 * <p><b>The access checks are still {@link BoardService}'s</b>: this depends on it, never the other
 * way round, so an invitation gets the same 404-not-403 answer every other board route gives.
 *
 * <p>Two deliberate asymmetries:
 *
 * <ul>
 *   <li><b>Re-inviting an address with a pending invitation sends no second mail</b> — it answers
 *       the existing row, since mailing on every click would let this application post somebody
 *       else's mailbox on request.</li>
 *   <li><b>An address already on the board is refused with no disclosure</b>, since only the
 *       board's owner can reach that refusal and they're already looking at the member list.</li>
 * </ul>
 */
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

    // --------------------------------------------------------------- the owner ---

    /**
     * Offers membership of {@code boardId} to an address. The address isn't looked up before the
     * row is written, and the response doesn't depend on whether it belongs to an account — the
     * lookup below only decides the mail's wording and language.
     */
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

        // An omitted role is MEMBER - V20's own default and the write access every invitation
        // offered before there was a choice, so a client that predates the picker keeps working.
        var role = request.role() == null ? BoardRole.MEMBER : request.role();
        var invitation = invitationRepository.save(new BoardInvitation(board, email, caller, role));
        announce(invitation, caller);
        return invitationMapper.apply(invitation);
    }

    /** Everything still outstanding on a board, for the owner who sent it. */
    public List<BoardInvitationDto> pendingFor(User caller, Integer boardId) {
        Board board = boardService.requireOwned(caller, boardId);
        return invitationRepository
                .findByBoardAndStatusOrderByIdAsc(board, InvitationStatus.PENDING)
                .stream()
                .map(invitationMapper)
                .toList();
    }

    /**
     * The owner's second thought. Anything that is not a pending invitation on this board — a
     * wrong id, another board's invitation, one already answered — is one {@code 404}.
     */
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

    // -------------------------------------------------------------- the invitee ---

    /** What the caller has been asked to join, matched on the address their account carries. */
    public List<BoardInvitationDto> myInvitations(User caller) {
        return pendingRowsFor(caller).stream().map(invitationMapper).toList();
    }

    /** Takes it up, at the role the offer named. This is the only way onto a board's member list. */
    public BoardDto accept(User caller, Integer invitationId) {
        var invitation = mineOrNotFound(caller, invitationId);
        invitation.resolveAs(InvitationStatus.ACCEPTED);
        invitationRepository.save(invitation);
        return boardMapper.apply(
                boardService.addAcceptedMember(invitation.getBoard(), caller, invitation.getRole()),
                caller);
    }

    /**
     * Turns it down. The row stays {@code DECLINED} rather than being deleted, so the owner sees
     * it answered rather than sees it vanish and sends it again.
     */
    public void decline(User caller, Integer invitationId) {
        var invitation = mineOrNotFound(caller, invitationId);
        invitation.resolveAs(InvitationStatus.DECLINED);
        invitationRepository.save(invitation);
    }

    // ------------------------------------------------------------------ helpers ---

    private List<BoardInvitation> pendingRowsFor(User caller) {
        String email = BoardInvitation.normaliseEmail(caller == null ? null : caller.getEmail());
        if (email == null || email.isBlank()) {
            return List.of();
        }
        return invitationRepository.findByEmailAndStatusOrderByIdAsc(email, InvitationStatus.PENDING);
    }

    /**
     * An invitation that is pending and addressed to this caller, or a {@code 404}. Matched by
     * address rather than by id, since the row is often written before the account existed.
     */
    private BoardInvitation mineOrNotFound(User caller, Integer invitationId) {
        String email = BoardInvitation.normaliseEmail(caller == null ? null : caller.getEmail());
        return invitationRepository.findById(invitationId)
                .filter(row -> row.getStatus() == InvitationStatus.PENDING)
                .filter(row -> row.getEmail() != null && row.getEmail().equals(email))
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVITATION_NOT_FOUND));
    }

    /**
     * Tells the mailbox, which is the only party that learns anything here. The language is the
     * recipient's when there is an account to read it from, and the inviter's otherwise — a better
     * guess than English, since somebody inviting a colleague usually shares their language.
     */
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
