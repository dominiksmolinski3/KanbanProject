package pl.myproject.kanbanproject2.board.invitation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardMapper;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.service.EmailService;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserMapper;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What an invitation has to do, and the two things it exists not to do.
 *
 * <p>The behaviour is easy to state and easy to get wrong in one specific direction, so most of
 * what is asserted here is about what the <em>inviter</em> is not told. An invite for an address
 * with an account and an invite for an address without one must be indistinguishable in the
 * response, or invitations are the same membership oracle {@code addMember} was with an extra
 * table. And nothing may reach a member list until the invitee has acted, or the acceptance is
 * decoration.
 */
class BoardInvitationServiceTest {

    private BoardInvitationRepository invitationRepository;
    private BoardService boardService;
    private UserRepository userRepository;
    private EmailService emailService;
    private BoardInvitationService service;

    private User owner;
    private User invitee;
    private Board board;

    @BeforeEach
    void setUp() {
        invitationRepository = mock(BoardInvitationRepository.class);
        boardService = mock(BoardService.class);
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);

        service = new BoardInvitationService(invitationRepository, new BoardInvitationMapper(),
                boardService, new BoardMapper(new UserMapper()), userRepository, emailService);

        owner = TenancyFixtures.user(1);
        owner.setName("Ada");
        invitee = TenancyFixtures.user(2);
        invitee.setEmail("invitee@example.test");
        board = TenancyFixtures.board(10, owner);

        when(boardService.requireOwned(owner, 10)).thenReturn(board);
        when(invitationRepository.save(any(BoardInvitation.class))).thenAnswer(call -> {
            BoardInvitation saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(500);
            }
            return saved;
        });
        when(invitationRepository.findByBoardAndEmailAndStatus(any(), anyString(), any()))
                .thenReturn(Optional.empty());
    }

    private BoardInvitation pending(String email) {
        var invitation = new BoardInvitation(board, email, owner);
        invitation.setId(500);
        return invitation;
    }

    @Nested
    @DisplayName("inviting")
    class Inviting {

        @Test
        @DisplayName("an address with an account and one without produce the same answer")
        void theAnswerDoesNotDependOnWhetherTheAddressHasAnAccount() {
            when(userRepository.findByEmail("known@example.test")).thenReturn(Optional.of(invitee));
            when(userRepository.findByEmail("unknown@example.test")).thenReturn(Optional.empty());

            var known = service.invite(owner, 10, new InviteRequest("known@example.test"));
            var unknown = service.invite(owner, 10, new InviteRequest("unknown@example.test"));

            // Everything except the address itself, which the caller supplied.
            assertThat(known.boardId()).isEqualTo(unknown.boardId());
            assertThat(known.boardName()).isEqualTo(unknown.boardName());
            assertThat(known.status()).isEqualTo(unknown.status());
            assertThat(known.invitedByName()).isEqualTo(unknown.invitedByName());
        }

        @Test
        @DisplayName("the address is stored lower-cased, so it can be matched against an account")
        void addressesAreNormalised() {
            service.invite(owner, 10, new InviteRequest("  Ada.Lovelace@Example.TEST "));

            var saved = ArgumentCaptor.forClass(BoardInvitation.class);
            verify(invitationRepository).save(saved.capture());
            assertThat(saved.getValue().getEmail()).isEqualTo("ada.lovelace@example.test");
        }

        @Test
        @DisplayName("nobody is put on the board by an invitation")
        void invitingDoesNotJoin() {
            service.invite(owner, 10, new InviteRequest("someone@example.test"));

            assertThat(board.everyone()).extracting(User::getId).containsExactly(1);
            verify(boardService, never()).addAcceptedMember(any(), any());
        }

        @Test
        @DisplayName("a second invite to the same address answers the first and sends no second mail")
        void reinvitingIsIdempotent() {
            var existing = pending("someone@example.test");
            when(invitationRepository.findByBoardAndEmailAndStatus(
                    board, "someone@example.test", InvitationStatus.PENDING))
                    .thenReturn(Optional.of(existing));

            var dto = service.invite(owner, 10, new InviteRequest("someone@example.test"));

            assertThat(dto.id()).isEqualTo(500);
            verify(invitationRepository, never()).save(any());
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("somebody already on the board is refused, which tells the owner nothing new")
        void alreadyAMemberIsRefused() {
            board.addMember(invitee);

            assertThatThrownBy(() ->
                    service.invite(owner, 10, new InviteRequest("INVITEE@example.test")))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ALREADY_BOARD_MEMBER);
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("a member cannot invite anybody - that is still the owner's to decide")
        void membersCannotInvite() {
            when(boardService.requireOwned(invitee, 10))
                    .thenThrow(new GlobalException(ExceptionIdentifier.NOT_BOARD_OWNER));

            assertThatThrownBy(() ->
                    service.invite(invitee, 10, new InviteRequest("x@example.test")))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.NOT_BOARD_OWNER);
        }
    }

    @Nested
    @DisplayName("the mail, which is the only place the two cases differ")
    class TheMail {

        @Test
        @DisplayName("an address with an account is told to sign in, in its own language")
        void aRegisteredInviteeIsMailedInTheirLanguage() {
            invitee.setLocale("pl");
            owner.setLocale("de");
            when(userRepository.findByEmail("invitee@example.test")).thenReturn(Optional.of(invitee));

            service.invite(owner, 10, new InviteRequest("invitee@example.test"));

            verify(emailService).sendBoardInvitation("invitee@example.test", board.getName(),
                    "Ada", true, Locale.forLanguageTag("pl"));
        }

        @Test
        @DisplayName("an address with no account is told to sign up, in the inviter's language")
        void anUnregisteredInviteeGetsTheInvitersLanguage() {
            owner.setLocale("de");
            when(userRepository.findByEmail("nobody@example.test")).thenReturn(Optional.empty());

            service.invite(owner, 10, new InviteRequest("nobody@example.test"));

            verify(emailService).sendBoardInvitation("nobody@example.test", board.getName(),
                    "Ada", false, Locale.forLanguageTag("de"));
        }
    }

    @Nested
    @DisplayName("answering one")
    class Answering {

        @BeforeEach
        void inviteeIsAsked() {
            when(invitationRepository.findByEmailAndStatusOrderByIdAsc(
                    "invitee@example.test", InvitationStatus.PENDING))
                    .thenReturn(List.of(pending("invitee@example.test")));
        }

        @Test
        @DisplayName("the invitee sees what they have been asked to join")
        void theInviteeSeesTheirOwn() {
            var mine = service.myInvitations(invitee);

            assertThat(mine).singleElement()
                    .satisfies(dto -> {
                        assertThat(dto.boardName()).isEqualTo(board.getName());
                        assertThat(dto.invitedByName()).isEqualTo("Ada");
                    });
        }

        @Test
        @DisplayName("accepting is what puts somebody on the board")
        void acceptingJoins() {
            var invitation = pending("invitee@example.test");
            when(invitationRepository.findById(500)).thenReturn(Optional.of(invitation));
            when(boardService.addAcceptedMember(board, invitee)).thenAnswer(call -> {
                board.addMember(invitee);
                return board;
            });

            var dto = service.accept(invitee, 500);

            assertThat(dto.members()).extracting(u -> u.id()).containsExactly(1, 2);
            assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
            assertThat(invitation.getRespondedAt()).isNotNull();
        }

        @Test
        @DisplayName("declining answers it without joining, and leaves the row where the owner can see it")
        void decliningDoesNotJoin() {
            var invitation = pending("invitee@example.test");
            when(invitationRepository.findById(500)).thenReturn(Optional.of(invitation));

            service.decline(invitee, 500);

            assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.DECLINED);
            verify(boardService, never()).addAcceptedMember(any(), any());
        }

        @Test
        @DisplayName("somebody else's invitation is a 404, not a 403")
        void anotherPersonsInvitationIsNotFound() {
            when(invitationRepository.findById(500))
                    .thenReturn(Optional.of(pending("someone.else@example.test")));

            assertThatThrownBy(() -> service.accept(invitee, 500))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVITATION_NOT_FOUND);
        }

        @Test
        @DisplayName("one already answered cannot be answered again")
        void anAnsweredInvitationIsSpent() {
            var invitation = pending("invitee@example.test");
            invitation.resolveAs(InvitationStatus.DECLINED);
            when(invitationRepository.findById(500)).thenReturn(Optional.of(invitation));

            assertThatThrownBy(() -> service.accept(invitee, 500))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVITATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("revoking")
    class Revoking {

        @Test
        @DisplayName("the owner can take one back, and it stops being pending")
        void theOwnerCanRevoke() {
            var invitation = pending("someone@example.test");
            when(invitationRepository.findById(500)).thenReturn(Optional.of(invitation));

            service.revoke(owner, 10, 500);

            assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.REVOKED);
        }

        @Test
        @DisplayName("an invitation belonging to another board is a 404 under this board's path")
        void anotherBoardsInvitationIsNotFound() {
            var elsewhere = TenancyFixtures.board(20, owner);
            var invitation = new BoardInvitation(elsewhere, "someone@example.test", owner);
            invitation.setId(500);
            when(invitationRepository.findById(500)).thenReturn(Optional.of(invitation));

            assertThatThrownBy(() -> service.revoke(owner, 10, 500))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVITATION_NOT_FOUND);
        }

        @Test
        @DisplayName("the owner's listing is the pending ones and nothing else")
        void theOwnerSeesWhatIsOutstanding() {
            when(invitationRepository.findByBoardAndStatusOrderByIdAsc(board, InvitationStatus.PENDING))
                    .thenReturn(List.of(pending("a@example.test")));

            assertThat(service.pendingFor(owner, 10))
                    .extracting(BoardInvitationDto::email)
                    .containsExactly("a@example.test");
        }
    }

    @Test
    @DisplayName("a caller with no address gets an empty listing rather than an exception")
    void anAddresslessCallerListsNothing() {
        var nameless = TenancyFixtures.user(9);
        nameless.setEmail(null);

        assertThat(service.myInvitations(nameless)).isEmpty();
        verify(invitationRepository, never()).findByEmailAndStatusOrderByIdAsc(any(), any());
    }

    @Test
    @DisplayName("nothing here mails on a path that did not create an invitation")
    void noStrayMail() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        service.invite(owner, 10, new InviteRequest("one@example.test"));

        verify(emailService).sendBoardInvitation(eq("one@example.test"), anyString(), anyString(),
                anyBoolean(), any(Locale.class));
    }
}
