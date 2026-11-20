package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.config.security.ratelimit.AuthRateLimiter;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.NOW;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.conversation;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.member;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.user;

class BoardChannelRulesTest {

    private ConversationAccess access;
    private ConversationRepository conversations;
    private ConversationMemberRepository members;
    private MlsMessageRepository messages;
    private BoardService boards;
    private ConversationNotifier notifier;
    private GroupRestarts restarts;
    private ConversationService service;

    private final User owner = user(1, "Ania");
    private final User editor = user(2, "Bartek");
    private final User viewer = user(3, "Celina");

    private Conversation channel;
    private ConversationMember ownerSeat;
    private ConversationMember editorSeat;
    private ConversationMember viewerSeat;

    @BeforeEach
    void setUp() {
        access = mock(ConversationAccess.class);
        conversations = mock(ConversationRepository.class);
        members = mock(ConversationMemberRepository.class);
        messages = mock(MlsMessageRepository.class);
        boards = mock(BoardService.class);
        notifier = mock(ConversationNotifier.class);
        restarts = mock(GroupRestarts.class);
        service = new ConversationService(access, conversations, mock(ConversationLocks.class), members, messages,
                mock(UserBlockRepository.class), mock(IdentityKeyRepository.class), mock(CryptoDeviceRepository.class),
                mock(UserRepository.class), boards, notifier, new ConversationMapper(), mock(AuthRateLimiter.class),
                restarts, Clock.fixed(NOW, ZoneOffset.UTC));
        when(conversations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(members.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        channel = conversation(10L, ConversationKind.BOARD, owner);
        ReflectionTestUtils.setField(channel, "boardId", 4);
        ownerSeat = member(1L, channel, owner, MemberRole.ADMIN, MemberState.ACTIVE, NOW);
        editorSeat = member(2L, channel, editor, MemberRole.MEMBER, MemberState.ACTIVE, NOW);
        viewerSeat = member(3L, channel, viewer, MemberRole.VIEWER, MemberState.ACTIVE, NOW);
        when(members.findAllIn(List.of(channel))).thenReturn(List.of(ownerSeat, editorSeat, viewerSeat));
        when(boards.namesOf(anyCollection())).thenReturn(Map.of(4, "Website relaunch"));
        for (ConversationMember seat : List.of(ownerSeat, editorSeat, viewerSeat)) {
            when(access.requireMember(seat.getUser(), 10L)).thenReturn(seat);
        }
    }

    @Test
    @DisplayName("a board channel is never started by hand")
    void cannotBeCreated() {
        assertThatThrownBy(() -> service.create(owner,
                new CreateConversationRequest(ConversationKind.BOARD, List.of(2), null, null)))
                .isInstanceOf(GlobalException.class);
        verify(conversations, never()).save(any());
    }

    @Test
    @DisplayName("its people follow the board, so nobody is added, removed or promoted by hand, and nobody leaves it")
    void rosterFollowsTheBoard() {
        assertThatThrownBy(() -> service.addMembers(owner, 10L, new AddMembersRequest(List.of(4))))
                .isInstanceOf(GlobalException.class);
        assertThatThrownBy(() -> service.removeMember(owner, 10L, 2)).isInstanceOf(GlobalException.class);
        assertThatThrownBy(() -> service.removeMember(editor, 10L, 2)).isInstanceOf(GlobalException.class);
        assertThatThrownBy(() -> service.setRole(owner, 10L, 2, new MemberRoleRequest(MemberRole.ADMIN)))
                .isInstanceOf(GlobalException.class);
        verify(members, never()).deleteMembership(any());
        verify(members, never()).save(any());
    }

    @Test
    @DisplayName("only the board's owner sets how much history newcomers see, and who can add is not a setting here")
    void ownerSetsHistory() {
        assertThatThrownBy(() -> service.update(editor, 10L,
                new UpdateConversationRequest(null, null, null, HistoryVisibility.ALL, null)))
                .isInstanceOfSatisfying(GlobalException.class,
                        exception -> assertThat(exception.getIdentifier()).isEqualTo(ExceptionIdentifier.NOT_BOARD_OWNER));

        ConversationDto updated = service.update(owner, 10L,
                new UpdateConversationRequest(null, null, null, HistoryVisibility.DAYS_30, WhoCanAdd.MEMBERS));

        assertThat(channel.getHistoryVisibility()).isEqualTo(HistoryVisibility.DAYS_30);
        assertThat(channel.getWhoCanAdd()).isEqualTo(WhoCanAdd.ADMINS);
        assertThat(updated.boardId()).isEqualTo(4);
        assertThat(updated.boardName()).isEqualTo("Website relaunch");
    }

    @Test
    @DisplayName("a member's own preferences are open to every member, viewers included")
    void viewersKeepPreferences() {
        ConversationDto muted = service.update(viewer, 10L,
                new UpdateConversationRequest(true, null, null, null, null));

        assertThat(muted.me().muted()).isTrue();
        assertThat(muted.me().role()).isEqualTo(MemberRole.VIEWER);
    }

    @Test
    @DisplayName("a deleted account leaves the channel without anyone being promoted, even when it was the owner")
    void deletedAccountLeavesWithoutPromotion() {
        var seat = mock(ConversationMemberRepository.MembershipRow.class);
        when(seat.getMemberId()).thenReturn(1L);
        when(seat.getConversationId()).thenReturn(10L);
        when(seat.getKind()).thenReturn(ConversationKind.BOARD);
        when(seat.getRole()).thenReturn(MemberRole.ADMIN);
        when(seat.getState()).thenReturn(MemberState.ACTIVE);
        var other = mock(ConversationMemberRepository.MembershipRow.class);
        when(other.getMemberId()).thenReturn(3L);
        when(other.getRole()).thenReturn(MemberRole.VIEWER);
        when(other.getState()).thenReturn(MemberState.ACTIVE);
        when(other.getJoinedAt()).thenReturn(NOW);
        when(members.membershipsOf(owner)).thenReturn(List.of(seat));
        when(members.membershipsIn(10L)).thenReturn(List.of(seat, other));
        when(members.usersIn(10L)).thenReturn(List.of(owner, viewer));

        service.forgetAccount(owner);

        verify(members).deleteMembership(1L);
        verify(members, never()).setRole(any(), any());
        verify(conversations, never()).deleteWithContents(any());
        verify(conversations).requireCommit(10L);
    }

    @Test
    @DisplayName("a board's last person leaving does not delete the channel here: deleting the board does")
    void lastPersonLeavingKeepsTheChannel() {
        var seat = mock(ConversationMemberRepository.MembershipRow.class);
        when(seat.getMemberId()).thenReturn(1L);
        when(seat.getConversationId()).thenReturn(10L);
        when(seat.getKind()).thenReturn(ConversationKind.BOARD);
        when(members.membershipsOf(owner)).thenReturn(List.of(seat));
        when(members.membershipsIn(10L)).thenReturn(List.of(seat));
        when(members.usersIn(10L)).thenReturn(List.of(owner));

        service.forgetAccount(owner);

        verify(conversations, never()).deleteWithContents(any());
        verify(members).deleteMembership(1L);
    }
}
