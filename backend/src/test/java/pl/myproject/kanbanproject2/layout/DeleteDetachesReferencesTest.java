package pl.myproject.kanbanproject2.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
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

    @Nested
    @DisplayName("deleting a swimlane")
    class DeletingARow {

        private final RowRepository rowRepository = Mockito.mock(RowRepository.class);
        private final TaskRepository taskRepository = Mockito.mock(TaskRepository.class);
        private final RowService rowService =
                new RowService(rowRepository, new RowMapper(new pl.myproject.kanbanproject2.task.TaskMapper()),
                        taskRepository);

        @Test
        @DisplayName("clears row_id on its tasks before the row is removed")
        void detachesTasksFirst() {
            Row row = new Row();
            row.setId(3);
            Task task = new Task();
            task.setId(11);
            task.setRow(row);
            row.setTasks(new ArrayList<>(List.of(task)));
            when(rowRepository.findById(3)).thenReturn(Optional.of(row));

            rowService.deleteRow(3);

            assertThat(task.getRow()).isNull();
            InOrder order = inOrder(taskRepository, rowRepository);
            order.verify(taskRepository).save(task);
            order.verify(rowRepository).delete(row);
        }

        @Test
        @DisplayName("an unknown swimlane is still a 404")
        void unknownRowIsNotFound() {
            when(rowRepository.findById(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> rowService.deleteRow(99))
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
        private final ColumnService columnService =
                new ColumnService(columnRepository,
                        new ColumnMapper(new pl.myproject.kanbanproject2.task.TaskMapper()), taskService);

        @Test
        @DisplayName("removes each task through the path that clears its history rows")
        void deletesTasksThroughTaskService() {
            Column column = new Column();
            column.setId(2);
            Task task = new Task();
            task.setId(7);
            column.setTasks(new ArrayList<>(List.of(task)));
            when(columnRepository.findById(2)).thenReturn(Optional.of(column));

            columnService.deleteColumn(2);

            // task_column_history.task_id is nullable = false, so the cascade on Column.tasks could
            // never have done this on its own.
            InOrder order = inOrder(taskService, columnRepository);
            order.verify(taskService).deleteTask(7);
            order.verify(columnRepository).delete(column);
        }

        @Test
        @DisplayName("an unknown column is still a 404")
        void unknownColumnIsNotFound() {
            when(columnRepository.findById(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> columnService.deleteColumn(99))
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
                new UserService(userRepository, new UserMapper(), taskRepository);

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
