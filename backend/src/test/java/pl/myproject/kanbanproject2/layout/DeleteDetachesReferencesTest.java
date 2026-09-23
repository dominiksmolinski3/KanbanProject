package pl.myproject.kanbanproject2.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnMapper;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.column.ColumnService;
import pl.myproject.kanbanproject2.layout.row.Row;
import pl.myproject.kanbanproject2.layout.row.RowMapper;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.layout.row.RowService;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.TaskService;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistory;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserMapper;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three deletes that used to answer 500.
 *
 * <p>Each failed on a foreign key rather than in Java, so the assertion here is that the reference
 * is gone before the delete is issued — the mapping never cleared it, and the database was the only
 * thing that noticed. A migration-backed integration test would exercise the constraint itself;
 * this covers the service contract that has to hold either way.
 */
class DeleteDetachesReferencesTest {

    private static final TenancyFixtures.Tenant TENANT = TenancyFixtures.tenant();
    private static final Board BOARD = TENANT.board();
    private static final User CALLER = TENANT.caller();

    @Nested
    @DisplayName("deleting a swimlane")
    class DeletingARow {

        private final RowRepository rowRepository = Mockito.mock(RowRepository.class);
        private final TaskRepository taskRepository = Mockito.mock(TaskRepository.class);
        private final RowService rowService =
                new RowService(rowRepository, new RowMapper(new pl.myproject.kanbanproject2.task.TaskMapper()),
                        taskRepository, TENANT.boardService(), mock(BoardEventPublisher.class));

        @Test
        @DisplayName("clears row_id on its tasks before the row is removed")
        void detachesTasksFirst() {
            Row row = new Row();
            row.setId(3);
            row.setBoard(BOARD);
            Task task = new Task();
            task.setId(11);
            task.setRow(row);
            task.setBoard(BOARD);
            row.setTasks(new ArrayList<>(List.of(task)));
            when(rowRepository.findById(3)).thenReturn(Optional.of(row));

            rowService.deleteRow(CALLER, 3);

            InOrder order = inOrder(taskRepository, rowRepository);
            order.verify(taskRepository).detachFromRow(row);
            order.verify(rowRepository).delete(row);
            // One statement, not a versioned save per task: the saves are what made a row deleted
            // alongside a column holding the same tasks answer 409. The loaded task is left alone,
            // or Hibernate would write it back with the same version check at flush.
            verify(taskRepository, never()).save(any());
            assertThat(task.getRow()).isSameAs(row);
            assertThat(row.getTasks()).isEmpty();
        }

        @Test
        @DisplayName("an unknown swimlane is still a 404")
        void unknownRowIsNotFound() {
            when(rowRepository.findById(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> rowService.deleteRow(CALLER, 99))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ROW_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleting a column")
    class DeletingAColumn {

        private final ColumnRepository columnRepository = Mockito.mock(ColumnRepository.class);
        private final TaskService taskService = Mockito.mock(TaskService.class);
        private final TaskColumnHistoryRepository historyRepository =
                Mockito.mock(TaskColumnHistoryRepository.class);
        private final ColumnService columnService =
                new ColumnService(columnRepository,
                        new ColumnMapper(new pl.myproject.kanbanproject2.task.TaskMapper()), taskService,
                        TENANT.boardService(), mock(BoardEventPublisher.class), historyRepository);

        @Test
        @DisplayName("removes each task through the path that clears its history rows")
        void deletesTasksThroughTaskService() {
            Column column = new Column();
            column.setId(2);
            column.setBoard(BOARD);
            Task task = new Task();
            task.setId(7);
            task.setBoard(BOARD);
            column.setTasks(new ArrayList<>(List.of(task)));
            when(columnRepository.findById(2)).thenReturn(Optional.of(column));
            when(historyRepository.findByColumn(column)).thenReturn(List.of());

            columnService.deleteColumn(CALLER, 2);

            // task_column_history.task_id is nullable = false, so the cascade on Column.tasks could
            // never have done this on its own.
            InOrder order = inOrder(taskService, columnRepository);
            order.verify(taskService).deleteTask(CALLER, 7);
            order.verify(columnRepository).delete(column);
        }

        @Test
        @DisplayName("lets go of every task before deleting any, so no flush can bring one back")
        void emptiesTheCollectionBeforeTheLoop() {
            Column column = new Column();
            column.setId(4);
            column.setBoard(BOARD);
            Task first = new Task();
            first.setId(8);
            first.setBoard(BOARD);
            Task second = new Task();
            second.setId(9);
            second.setBoard(BOARD);
            column.setTasks(new ArrayList<>(List.of(first, second)));
            when(columnRepository.findById(4)).thenReturn(Optional.of(column));
            when(historyRepository.findByColumn(column)).thenReturn(List.of());
            // What the column still holds at the moment each task is deleted. Column.tasks
            // cascades ALL, so a deleted task left in it is persisted again by the next query's
            // flush - which made every column with two or more cards a 500.
            var heldDuringDeletes = new ArrayList<Integer>();
            org.mockito.Mockito.doAnswer(call -> {
                heldDuringDeletes.add(column.getTasks().size());
                return null;
            }).when(taskService).deleteTask(org.mockito.ArgumentMatchers.eq(CALLER), org.mockito.ArgumentMatchers.anyInt());

            columnService.deleteColumn(CALLER, 4);

            assertThat(heldDuringDeletes).containsExactly(0, 0);
            verify(taskService).deleteTask(CALLER, 8);
            verify(taskService).deleteTask(CALLER, 9);
        }

        @Test
        @DisplayName("clears column_id on history rows left by tasks that have since moved on")
        void detachesStrandedHistoryFirst() {
            Column column = new Column();
            column.setId(2);
            column.setBoard(BOARD);
            column.setTasks(new ArrayList<>());
            when(columnRepository.findById(2)).thenReturn(Optional.of(column));

            // Nothing is in the column right now - the task that once passed through it moved on to
            // a different column - but the row from that visit still points here.
            TaskColumnHistory strandedEntry = new TaskColumnHistory();
            strandedEntry.setColumn(column);
            when(historyRepository.findByColumn(column)).thenReturn(List.of(strandedEntry));

            columnService.deleteColumn(CALLER, 2);

            assertThat(strandedEntry.getColumn()).isNull();
            InOrder order = inOrder(historyRepository, columnRepository);
            order.verify(historyRepository).saveAll(List.of(strandedEntry));
            order.verify(columnRepository).delete(column);
        }

        @Test
        @DisplayName("puts the board's flow definition back on the default when it named this column")
        void forgetsTheFlowColumn() {
            // A board of its own, since BOARD is shared across this class and this test changes it.
            var board = TenancyFixtures.board(78, CALLER);
            Column column = new Column();
            column.setId(3);
            column.setBoard(board);
            column.setTasks(new ArrayList<>());
            Column other = new Column();
            other.setId(4);
            board.setFlowStartColumn(other);
            board.setFlowDoneColumn(column);
            when(columnRepository.findById(3)).thenReturn(Optional.of(column));
            when(historyRepository.findByColumn(column)).thenReturn(List.of());

            columnService.deleteColumn(CALLER, 3);

            // V23's ON DELETE SET NULL covers the database; this covers a board already loaded in
            // the same transaction, which would otherwise flush the deleted id straight back.
            assertThat(board.getFlowDoneColumn()).isNull();
            assertThat(board.getFlowStartColumn()).isSameAs(other);
        }

        @Test
        @DisplayName("an unknown column is still a 404")
        void unknownColumnIsNotFound() {
            when(columnRepository.findById(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> columnService.deleteColumn(CALLER, 99))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.COLUMN_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleting a user")
    class DeletingAUser {

        private final UserRepository userRepository = Mockito.mock(UserRepository.class);
        private final TaskRepository taskRepository = Mockito.mock(TaskRepository.class);
        private final UserService userService =
                new UserService(userRepository, new UserMapper(), taskRepository, TENANT.boardService());

        @Test
        @DisplayName("clears the user_task join rows from the owning side before removing the account")
        void unassignsTasksFirst() {
            User user = new User();
            user.setId(5);
            Task task = new Task();
            task.setId(11);
            task.setUsers(new HashSet<>(Set.of(user)));
            user.setTasks(new HashSet<>(Set.of(task)));
            when(userRepository.findById(5)).thenReturn(Optional.of(user));

            userService.deleteUser(5);

            // Task owns user_task; nothing on the User side would have cleared it.
            assertThat(task.getUsers()).isEmpty();
            InOrder order = inOrder(taskRepository, userRepository);
            order.verify(taskRepository).save(task);
            order.verify(userRepository).delete(user);
        }

        @Test
        @DisplayName("the tasks themselves survive the account")
        void keepsTheTasks() {
            User user = new User();
            user.setId(5);
            Task task = new Task();
            task.setId(11);
            task.setUsers(new HashSet<>(Set.of(user)));
            user.setTasks(new HashSet<>(Set.of(task)));
            when(userRepository.findById(5)).thenReturn(Optional.of(user));

            userService.deleteUser(5);

            verify(taskRepository, Mockito.never()).delete(any(Task.class));
        }

        @Test
        @DisplayName("an unknown user is still a 404")
        void unknownUserIsNotFound() {
            when(userRepository.findById(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.deleteUser(99))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.USER_NOT_FOUND);
        }
    }
}
