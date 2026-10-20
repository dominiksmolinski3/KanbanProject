package pl.myproject.kanbanproject2.board.invitation;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardDto;
import pl.myproject.kanbanproject2.board.BoardMapper;
import pl.myproject.kanbanproject2.board.BoardRole;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.mail.EmailService;
import pl.myproject.kanbanproject2.user.SupportedLocales;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Transactional
@Service
public class BoardInvitationService {

    private final BoardInvitationRepository invitationRepository;
    private final BoardInvitationMapper invitationMapper;
    private final BoardService boardService;
    private final BoardMapper boardMapper;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final BoardEventPublisher boardEvents;
    private final Duration expiry;
    private final int dailyLimit;
    private final Clock clock;

    @Autowired
    public BoardInvitationService(BoardInvitationRepository invitationRepository,
                                  BoardInvitationMapper invitationMapper,
                                  BoardService boardService,
                                  BoardMapper boardMapper,
                                  UserRepository userRepository,
                                  EmailService emailService,
                                  BoardEventPublisher boardEvents,
                                  @Value("${app.invitations.expiry-days:14}") long expiryDays,
                                  @Value("${app.invitations.daily-limit:20}") int dailyLimit) {
        this(invitationRepository, invitationMapper, boardService, boardMapper, userRepository, emailService,
                boardEvents, Duration.ofDays(expiryDays), dailyLimit, Clock.systemDefaultZone());
    }

    BoardInvitationService(BoardInvitationRepository invitationRepository,
                           BoardInvitationMapper invitationMapper,
                           BoardService boardService,
                           BoardMapper boardMapper,
                           UserRepository userRepository,
                           EmailService emailService,
                           BoardEventPublisher boardEvents,
                           Duration expiry,
                           int dailyLimit,
                           Clock clock) {
        this.invitationRepository = invitationRepository;
        this.invitationMapper = invitationMapper;
        this.boardService = boardService;
        this.boardMapper = boardMapper;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.boardEvents = boardEvents;
        this.expiry = expiry;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    public BoardInvitationDto invite(User caller, Integer boardId, InviteRequest request) {
        Board board = boardService.requireOwned(caller, boardId);
        String email = BoardInvitation.normaliseEmail(request.email());

        if (board.everyone().stream().anyMatch(member -> email.equalsIgnoreCase(member.getEmail()))) {
            throw new GlobalException(ExceptionIdentifier.ALREADY_BOARD_MEMBER);
        }

        var existing = invitationRepository
                .findByBoardAndEmailAndStatus(board, email, InvitationStatus.PENDING);
        if (existing.isPresent()) {
            if (isLive(existing.get())) {
                return invitationMapper.apply(existing.get());
            }
            // The pending row has to stop being PENDING before the new one, or the partial unique index refuses it.
            existing.get().resolveAs(InvitationStatus.EXPIRED);
            invitationRepository.saveAndFlush(existing.get());
        }
        requireUnderDailyLimit(caller);

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
                .filter(this::isLive)
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
        return invitationRepository.findByEmailAndStatusOrderByIdAsc(email, InvitationStatus.PENDING).stream()
                .filter(this::isLive)
                .toList();
    }

    private BoardInvitation mineOrNotFound(User caller, Integer invitationId) {
        String email = BoardInvitation.normaliseEmail(caller == null ? null : caller.getEmail());
        return invitationRepository.findById(invitationId)
                .filter(row -> row.getStatus() == InvitationStatus.PENDING)
                .filter(this::isLive)
                .filter(row -> row.getEmail() != null && row.getEmail().equals(email))
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVITATION_NOT_FOUND));
    }

    private boolean isLive(BoardInvitation invitation) {
        return invitation.getCreatedAt() != null
                && invitation.getCreatedAt().isAfter(LocalDateTime.now(clock).minus(expiry));
    }

    private void requireUnderDailyLimit(User inviter) {
        long sent = invitationRepository.countByInvitedByAndCreatedAtAfter(
                inviter, LocalDateTime.now(clock).minusHours(24));
        if (sent >= dailyLimit) {
            throw new GlobalException(ExceptionIdentifier.TOO_MANY_REQUESTS,
                    "Wysłano już " + dailyLimit + " zaproszeń w ciągu ostatniej doby");
        }
    }

    private void announce(BoardInvitation invitation, User inviter) {
        var account = userRepository.findByEmail(invitation.getEmail());
        account.ifPresent(invitee -> boardEvents.boardsChangedFor(List.of(invitee)));
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
