package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardDeleting;
import pl.myproject.kanbanproject2.board.BoardRole;
import pl.myproject.kanbanproject2.board.BoardRosterChanged;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.user.User;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.NOW;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.conversation;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.user;

class BoardChannelsTest {

    private ConversationRepository conversations;
    private ConversationMemberRepository members;
    private ConversationLocks locks;
    private ConversationNotifier notifier;
    private GroupRestarts restarts;
    private BoardService boards;
    private BoardChannels channels;

    private final User owner = user(1, "Ania");
    private final User editor = user(2, "Bartek");
    private final User viewer = user(3, "Celina");
    private Board board;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        members = mock(ConversationMemberRepository.class);
        locks = mock(ConversationLocks.class);
        notifier = mock(ConversationNotifier.class);
        restarts = mock(GroupRestarts.class);
        boards = mock(BoardService.class);
        channels = new BoardChannels(conversations, members, locks, notifier, restarts, boards,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(conversations.save(any())).thenAnswer(invocation -> {
            Conversation saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 10L);
            }
            return saved;
        });
        when(members.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        board = new Board("Website relaunch", owner);
        board.setId(4);
        board.addMember(editor);
        board.addMember(viewer);
        when(boards.memberRoles(board)).thenReturn(Map.of(1, BoardRole.MEMBER, 2, BoardRole.MEMBER, 3, BoardRole.VIEWER));
    }

    private ConversationMemberRepository.RosterRow seat(long memberId, int userId, MemberRole role) {
        var row = mock(ConversationMemberRepository.RosterRow.class);
        when(row.getMemberId()).thenReturn(memberId);
        when(row.getUserId()).thenReturn(userId);
        when(row.getRole()).thenReturn(role);
        return row;
    }

    private void roster(ConversationMemberRepository.RosterRow... rows) {
        List<ConversationMemberRepository.RosterRow> list = List.of(rows);
        when(members.rosterOf(10L)).thenReturn(list);
    }

    @Test
    @DisplayName("a board without a channel gets one, with the owner as admin and viewers marked read-only")
    void opensAChannel() {
        when(conversations.findIdByBoardId(4)).thenReturn(Optional.empty());

        channels.on(new BoardRosterChanged(board));

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(ConversationKind.BOARD);
        assertThat(saved.getValue().getBoardId()).isEqualTo(4);
        assertThat(saved.getValue().getEpoch()).isZero();
        ArgumentCaptor<ConversationMember> seats = ArgumentCaptor.forClass(ConversationMember.class);
        verify(members, org.mockito.Mockito.times(3)).save(seats.capture());
        assertThat(seats.getAllValues())
                .extracting(seat -> seat.getUser().getId(), ConversationMember::getRole, ConversationMember::getState)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(1, MemberRole.ADMIN, MemberState.ACTIVE),
                        org.assertj.core.groups.Tuple.tuple(2, MemberRole.MEMBER, MemberState.ACTIVE),
                        org.assertj.core.groups.Tuple.tuple(3, MemberRole.VIEWER, MemberState.ACTIVE));
        verify(notifier).notify(anyList(), any(ConversationEvent.class));
    }

    @Test
    @DisplayName("someone who joins the board is seated and the channel waits for a commit that adds their devices")
    void seatsAJoiner() {
        when(conversations.findIdByBoardId(4)).thenReturn(Optional.of(10L));
        Conversation existing = conversation(10L, ConversationKind.BOARD, owner);
        when(locks.lock(10L)).thenReturn(existing);
        when(members.usersIn(10L)).thenReturn(List.of(owner, editor));
        roster(seat(1L, 1, MemberRole.ADMIN), seat(2L, 2, MemberRole.MEMBER));

        channels.on(new BoardRosterChanged(board));

        ArgumentCaptor<ConversationMember> seated = ArgumentCaptor.forClass(ConversationMember.class);
        verify(members).save(seated.capture());
        assertThat(seated.getValue().getUser().getId()).isEqualTo(3);
        assertThat(seated.getValue().getRole()).isEqualTo(MemberRole.VIEWER);
        assertThat(existing.isNeedsCommit()).isTrue();
        verify(members, never()).deleteMembership(any());
    }

    @Test
    @DisplayName("someone who leaves the board loses their seat at once, and the channel waits for a commit that removes them")
    void unseatsALeaver() {
        board.removeMember(viewer);
        when(conversations.findIdByBoardId(4)).thenReturn(Optional.of(10L));
        when(members.usersIn(10L)).thenReturn(List.of(owner, editor, viewer));
        roster(seat(1L, 1, MemberRole.ADMIN), seat(2L, 2, MemberRole.MEMBER), seat(3L, 3, MemberRole.VIEWER));
        when(boards.memberRoles(board)).thenReturn(Map.of(1, BoardRole.MEMBER, 2, BoardRole.MEMBER));

        channels.on(new BoardRosterChanged(board));

        verify(members).deleteMembership(3L);
        verify(conversations).requireCommit(10L);
        verify(restarts).restartOrphans(List.of(10L));
        ArgumentCaptor<List<User>> told = ArgumentCaptor.forClass(List.class);
        verify(notifier).notify(told.capture(), any(ConversationEvent.class));
        assertThat(told.getValue()).as("the person who left is told too, so their screen drops the channel")
                .containsExactlyInAnyOrder(owner, editor, viewer);
    }

    @Test
    @DisplayName("a changed board role, or a new owner, changes the seat's role and nothing else")
    void followsRoleChanges() {
        when(conversations.findIdByBoardId(4)).thenReturn(Optional.of(10L));
        when(members.usersIn(10L)).thenReturn(List.of(owner, editor, viewer));
        roster(seat(1L, 1, MemberRole.ADMIN), seat(2L, 2, MemberRole.VIEWER), seat(3L, 3, MemberRole.VIEWER));

        channels.on(new BoardRosterChanged(board));

        verify(members).setRole(2L, MemberRole.MEMBER);
        verify(members, never()).setRole(eq(1L), any());
        verify(members, never()).setRole(eq(3L), any());
        verify(conversations, never()).requireCommit(any());
        verify(members, never()).save(any());
    }

    @Test
    @DisplayName("a rename, which changes no seat, still tells everyone to read the channel again")
    void announcesARename() {
        when(conversations.findIdByBoardId(4)).thenReturn(Optional.of(10L));
        when(members.usersIn(10L)).thenReturn(List.of(owner, editor, viewer));
        roster(seat(1L, 1, MemberRole.ADMIN), seat(2L, 2, MemberRole.MEMBER), seat(3L, 3, MemberRole.VIEWER));

        channels.on(new BoardRosterChanged(board));

        verify(notifier).notify(anyList(), any(ConversationEvent.class));
        verify(conversations, never()).requireCommit(any());
    }

    @Test
    @DisplayName("deleting the board deletes its channel and tells the people in it")
    void closesTheChannel() {
        when(conversations.findIdByBoardId(4)).thenReturn(Optional.of(10L));
        when(members.usersIn(10L)).thenReturn(List.of(owner, editor));

        channels.on(new BoardDeleting(board));

        verify(conversations).deleteWithContents(10L);
        verify(notifier).notify(List.of(owner, editor), new ConversationEvent(ConversationEventType.MEMBERS, 10L, null, null));
    }

    @Test
    @DisplayName("a board that never had a channel deletes quietly")
    void noChannelToClose() {
        when(conversations.findIdByBoardId(4)).thenReturn(Optional.empty());

        channels.on(new BoardDeleting(board));

        verify(conversations, never()).deleteWithContents(any());
    }
}
