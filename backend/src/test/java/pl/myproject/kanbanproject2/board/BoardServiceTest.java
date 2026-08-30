package pl.myproject.kanbanproject2.board;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistory;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserMapper;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules the whole authorization model rests on, asserted where they are written rather than
 * once per feature that consults them.
 */
class BoardServiceTest {

    private BoardRepository boardRepository;
    private ColumnRepository columnRepository;
    private RowRepository rowRepository;
    private TaskRepository taskRepository;
    private TaskColumnHistoryRepository historyRepository;
    private UserRepository userRepository;
    private BoardService boardService;

    private User owner;
    private User member;
    private User stranger;

    @BeforeEach
    void setUp() {
        boardRepository = mock(BoardRepository.class);
        columnRepository = mock(ColumnRepository.class);
        rowRepository = mock(RowRepository.class);
        taskRepository = mock(TaskRepository.class);
        historyRepository = mock(TaskColumnHistoryRepository.class);
        userRepository = mock(UserRepository.class);

        boardService = new BoardService(boardRepository, columnRepository, rowRepository,
                taskRepository, historyRepository, userRepository, new BoardMapper(new UserMapper()));

        owner = TenancyFixtures.user(1);
        member = TenancyFixtures.user(2);
        stranger = TenancyFixtures.user(3);

        when(boardRepository.save(any(Board.class))).thenAnswer(call -> call.getArgument(0));
        when(columnRepository.save(any(Column.class))).thenAnswer(call -> call.getArgument(0));
    }

    private Board boardOf(User boardOwner, User... members) {
        var board = TenancyFixtures.board(10, boardOwner);
        for (User extra : members) {
            board.addMember(extra);
        }
        when(boardRepository.findWithMembersById(10)).thenReturn(Optional.of(board));
        return board;
    }

    @Nested
    @DisplayName("who can see a board")
    class Visibility {

        @Test
        @DisplayName("the owner and its members can; nobody else can")
        void membershipDecides() {
            var board = boardOf(owner, member);

            assertThat(board.isVisibleTo(owner)).isTrue();
            assertThat(board.isVisibleTo(member)).isTrue();
            assertThat(board.isVisibleTo(stranger)).isFalse();
        }

        @Test
        @DisplayName("a stranger gets 404, not 403 - a 403 would confirm the board exists")
        void invisibleBoardsAnswerAsMissing() {
            boardOf(owner);

            assertThatThrownBy(() -> boardService.requireVisible(stranger, 10))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.BOARD_NOT_FOUND)
                    .extracting(id -> ((ExceptionIdentifier) id).getStatus().value())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a member who is not the owner gets 403 on the owner-only routes")
        void membersAreNotOwners() {
            boardOf(owner, member);

            assertThatThrownBy(() -> boardService.requireOwned(member, 10))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.NOT_BOARD_OWNER);
        }

        @Test
        @DisplayName("an ownerless board belongs to nobody, not to everybody")
        void nullOwnerIsNotAWildcard() {
            var unclaimed = TenancyFixtures.board(10, null);

            assertThat(unclaimed.isOwnedBy(owner)).isFalse();
            assertThat(unclaimed.isVisibleTo(owner)).isFalse();
        }

        @Test
        @DisplayName("membership compares ids - the caller and the member are different instances")
        void comparesOnIdRatherThanIdentity() {
            var board = boardOf(owner, member);
            // The caller arrives from the JWT filter; the members come out of the persistence
            // context. User does not implement equals, so identity comparison would fail here.
            var sameAccountDifferentInstance = TenancyFixtures.user(2);

            assertThat(board.isVisibleTo(sameAccountDifferentInstance)).isTrue();
        }
    }

    @Nested
    @DisplayName("provisioning")
    class Provisioning {

        @Test
        @DisplayName("a new account gets a board with the default stages on it")
        void createsABoardWithDefaults() {
            when(boardRepository.findFirstByOwnerIsNullOrderByIdAsc()).thenReturn(Optional.empty());

            var board = boardService.provisionFor(owner);

            assertThat(board.getOwner()).isEqualTo(owner);
            assertThat(board.isVisibleTo(owner)).isTrue();

            ArgumentCaptor<Column> seeded = ArgumentCaptor.forClass(Column.class);
            verify(columnRepository, org.mockito.Mockito.times(8)).save(seeded.capture());
            assertThat(seeded.getAllValues())
                    .extracting(Column::getName)
                    .startsWith("New Issues", "Icebox")
                    .endsWith("Done", "Closed");
            assertThat(seeded.getAllValues()).allMatch(column -> column.getBoard() == board);
        }

        @Test
        @DisplayName("the first account adopts the migration's unclaimed board instead of shadowing it")
        void adoptsTheUnclaimedBoard() {
            var legacy = TenancyFixtures.board(1, null);
            when(boardRepository.findFirstByOwnerIsNullOrderByIdAsc()).thenReturn(Optional.of(legacy));

            var board = boardService.provisionFor(owner);

            assertThat(board).isSameAs(legacy);
            assertThat(board.isOwnedBy(owner)).isTrue();
            // Adopting rather than creating is what keeps V3's eight seeded stages reachable; a
            // second set would mean the fresh install came up with sixteen.
            verify(columnRepository, never()).save(any());
        }

        @Test
        @DisplayName("a caller with a board is given it, not a second one")
        void doesNotProvisionTwice() {
            var existing = TenancyFixtures.board(10, owner);
            when(boardRepository.findByOwnerOrderByIdAsc(owner)).thenReturn(List.of(existing));

            assertThat(boardService.defaultFor(owner)).isSameAs(existing);
            verify(boardRepository, never()).findFirstByOwnerIsNullOrderByIdAsc();
        }

        @Test
        @DisplayName("a member who owns nothing falls back to the board they were invited to")
        void fallsBackToAJoinedBoard() {
            var joined = TenancyFixtures.board(10, owner);
            joined.addMember(member);
            when(boardRepository.findByOwnerOrderByIdAsc(member)).thenReturn(List.of());
            when(boardRepository.findVisibleTo(member)).thenReturn(List.of(joined));

            assertThat(boardService.defaultFor(member)).isSameAs(joined);
        }
    }

    @Nested
    @DisplayName("members")
    class Members {

        @Test
        @DisplayName("the owner can add somebody by the address they signed up with")
        void addsByEmail() {
            var board = boardOf(owner);
            when(userRepository.findByEmail("user2@example.com")).thenReturn(Optional.of(member));

            var dto = boardService.addMember(owner, 10, new AddMemberRequest("user2@example.com"));

            assertThat(dto.members()).extracting(u -> u.id()).contains(1, 2);
        }

        @Test
        @DisplayName("an address with no account changes nothing and is not reported as an error")
        void unknownEmailIsNotAnError() {
            var board = boardOf(owner);
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

            var dto = boardService.addMember(owner, 10, new AddMemberRequest("nobody@example.com"));

            assertThat(dto.members()).extracting(u -> u.id()).containsExactly(1);
            assertThat(board.getMembers()).hasSize(1);
        }

        @Test
        @DisplayName("a member cannot add anybody - that is the owner's to decide")
        void membersCannotInvite() {
            boardOf(owner, member);

            assertThatThrownBy(() ->
                    boardService.addMember(member, 10, new AddMemberRequest("x@example.com")))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.NOT_BOARD_OWNER);
        }

        @Test
        @DisplayName("a member can remove themselves, and is taken off the board's tasks on the way out")
        void leavingUnassigns() {
            var board = boardOf(owner, member);
            var task = new Task();
            task.setId(5);
            task.setBoard(board);
            task.getUsers().add(member);
            when(taskRepository.findByBoardOrderByIdAsc(board)).thenReturn(List.of(task));
            when(userRepository.findById(2)).thenReturn(Optional.of(member));

            boardService.removeMember(member, 10, 2);

            assertThat(board.isVisibleTo(member)).isFalse();
            // A stale assignment would keep their name on a board they can no longer open, and
            // keep counting against their WIP limit for work they cannot reach.
            assertThat(task.getUsers()).isEmpty();
        }

        @Test
        @DisplayName("a member cannot remove another member")
        void membersCannotEvictEachOther() {
            var third = TenancyFixtures.user(4);
            boardOf(owner, member, third);

            assertThatThrownBy(() -> boardService.removeMember(member, 10, 4))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.NOT_BOARD_OWNER);
        }

        @Test
        @DisplayName("the owner cannot be removed, including by themselves")
        void theOwnerStays() {
            boardOf(owner, member);

            // Nothing anywhere can appoint a new owner, so a board without one would be a board
            // nobody could rename, share or delete.
            assertThatThrownBy(() -> boardService.removeMember(owner, 10, 1))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.CANNOT_REMOVE_BOARD_OWNER);
        }

        @Test
        @DisplayName("a stranger cannot even see the member list")
        void strangersCannotReadMembers() {
            boardOf(owner, member);

            assertThatThrownBy(() -> boardService.getBoard(stranger, 10))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.BOARD_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleting a board")
    class Deleting {

        @Test
        @DisplayName("unwinds the history and the parent links before the tasks, then the layout")
        void unwindsInTheOrderTheConstraintsRequire() {
            var board = boardOf(owner);
            var parent = new Task();
            parent.setId(1);
            parent.setBoard(board);
            var child = new Task();
            child.setId(2);
            child.setBoard(board);
            child.setParentTask(parent);
            parent.setChildTasks(new java.util.HashSet<>(java.util.Set.of(child)));

            var tasks = List.of(parent, child);
            when(taskRepository.findByBoardOrderByIdAsc(board)).thenReturn(tasks);
            when(historyRepository.findByTaskIn(tasks)).thenReturn(List.of(new TaskColumnHistory()));
            when(columnRepository.findByBoardOrderByPositionAsc(board)).thenReturn(new ArrayList<>());
            when(rowRepository.findByBoardOrderByPositionAsc(board)).thenReturn(new ArrayList<>());

            boardService.deleteBoard(owner, 10);

            // task_column_history.task_id is not nullable and nothing cascades to it; a parent
            // cannot be deleted while a child still points at it. Both are why this is not a
            // single deleteAll.
            assertThat(child.getParentTask()).isNull();
            verify(historyRepository).deleteAll(any());
            verify(taskRepository).deleteAll(tasks);
            verify(boardRepository).delete(board);
        }

        @Test
        @DisplayName("a member cannot delete the board they were invited to")
        void membersCannotDelete() {
            boardOf(owner, member);

            assertThatThrownBy(() -> boardService.deleteBoard(member, 10))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.NOT_BOARD_OWNER);

            verify(boardRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("the same-board invariant")
    class SameBoard {

        @Test
        @DisplayName("two objects on different boards are refused even when the caller is on both")
        void refusesACrossBoardMove() {
            var first = TenancyFixtures.board(10, owner);
            var second = TenancyFixtures.board(11, owner);

            assertThatThrownBy(() -> boardService.requireSameBoard(first, second))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.BOARD_MISMATCH);
        }

        @Test
        @DisplayName("the same board passes")
        void allowsTheSameBoard() {
            var board = TenancyFixtures.board(10, owner);
            boardService.requireSameBoard(board, board);
        }
    }
}
