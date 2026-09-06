package pl.myproject.kanbanproject2.board.invitation;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardDto;
import pl.myproject.kanbanproject2.board.BoardMapper;
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
 * Invitations: an owner offers membership, and the person offered decides.
 *
 * <p>This replaces {@code BoardService.addMember}, which put an account on a board the moment an
 * owner typed its address and answered with the board's member list - so an owner could diff that
 * list and learn whether an address had an account here, which is the membership oracle the
 * unauthenticated routes are all written to avoid. Both halves are closed here: the response to an
 * invite is the invitation and never the board, and nothing happens to the member list until the
 * invitee acts.
 *
 * <p><b>The access checks are still {@link BoardService}'s.</b> This depends on it, never the
 * other way round, so an invitation cannot be created or read without the same 404-not-403 answer
 * every other route on a board gives.
 *
 * <p>Two deliberate asymmetries worth knowing about:
 *
 * <ul>
 *   <li><b>Re-inviting an address that already has a pending invitation sends no second mail.</b>
 *       It answers the invitation that already exists. An owner clicking twice is the common case,
 *       and a route that mails on every click is a way to have this application post somebody
 *       else's mailbox on request. It is not a complete answer - a determined caller can make one
 *       board per invitation - and the honest bound for that is a limit on boards, which does not
 *       exist yet.</li>
 *   <li><b>An address that is already on the board is refused, and that refusal discloses
 *       nothing.</b> Only the board's owner can reach it, and the owner is already looking at the
 *       member list on the same screen.</li>
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
     * Offers membership of {@code boardId} to an address.
     *
     * <p>The address is not looked up before the row is written, and the answer does not depend on
     * whether it belongs to an account. What the lookup below decides is only which of two
     * wordings goes to the mailbox and which language it is written in - facts the recipient can
     * see and the inviter cannot.
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

        var invitation = invitationRepository.save(new BoardInvitation(board, email, caller));
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
     * The owner's second thought. Anything that is not a pending invitation on this board - a
     * wrong id, another board's invitation, one already answered - is one {@code 404}, for the
     * reason every id in this application answers 404: they are small and sequential.
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

    /** Takes it up. This is the only way onto a board's member list. */
    public BoardDto accept(User caller, Integer invitationId) {
        var invitation = mineOrNotFound(caller, invitationId);
        invitation.resolveAs(InvitationStatus.ACCEPTED);
        invitationRepository.save(invitation);
        return boardMapper.apply(
                boardService.addAcceptedMember(invitation.getBoard(), caller), caller);
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
     * An invitation that is pending and addressed to this caller, or a {@code 404}.
     *
     * <p>Matched by address rather than by id, because in the general case the row was written
     * before the account existed - which is also why an invitation is never "the caller's" in the
     * database's own sense and the check has to be made here on every call.
     */
    private BoardInvitation mineOrNotFound(User caller, Integer invitationId) {
        String email = BoardInvitation.normaliseEmail(caller == null ? null : caller.getEmail());
        return invitationRepository.findById(invitationId)
                .filter(row -> row.getStatus() == InvitationStatus.PENDING)
                .filter(row -> row.getEmail() != null && row.getEmail().equals(email))
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVITATION_NOT_FOUND));
    }

    /**
     * Tells the mailbox, which is the only party that learns anything here.
     *
     * <p>The language is the recipient's when there is an account to read it from and the
     * inviter's when there is not - a guess, in the same spirit as signup guessing from
     * {@code Accept-Language}, and a better one than English: somebody inviting a colleague is
     * usually inviting them into their own language.
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
