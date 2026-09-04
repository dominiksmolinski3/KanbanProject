package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.attachment.TaskAttachmentService;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryMapper;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deadline sweep flips {@code expired} on both crossings; only the crossing <em>into</em>
 * expired should raise a notification, and it should raise it after the flag is saved rather than
 * instead of saving it.
 */
class TaskServiceDeadlineSweepTest {

    private TaskRepository taskRepository;
    private DeadlineNotifier deadlineNotifier;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        deadlineNotifier = mock(DeadlineNotifier.class);
        when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        taskService = new TaskService(
                taskRepository,
                mock(UserRepository.class),
                new TaskMapper(),
                mock(UserService.class),
                mock(TaskColumnHistoryRepository.class),
                mock(TaskColumnHistoryMapper.class),
                mock(ColumnRepository.class),
                mock(RowRepository.class),
                mock(BoardService.class),
                deadlineNotifier,
                mock(TaskAttachmentService.class));
    }

    private static Task task(LocalDateTime deadline, boolean expired) {
        var task = new Task();
        task.setId(1);
        task.setDeadline(deadline);
        task.setExpired(expired);
        return task;
    }

    @Test
    @DisplayName("a task that just passed its deadline is flagged and its assignees notified")
    void notifiesOnCrossingIntoExpired() {
        Task task = task(LocalDateTime.now().minusMinutes(5), false);
        when(taskRepository.findAllByDeadlineIsNotNull()).thenReturn(List.of(task));

        taskService.checkAllTasksDeadlines();

        assertThat(task.isExpired()).isTrue();
        verify(taskRepository).save(task);
        verify(deadlineNotifier).notifyExpired(task);
    }

    @Test
    @DisplayName("a task whose deadline was pushed back is un-flagged without a second mail")
    void doesNotNotifyOnCrossingOutOfExpired() {
        Task task = task(LocalDateTime.now().plusDays(1), true);
        when(taskRepository.findAllByDeadlineIsNotNull()).thenReturn(List.of(task));

        taskService.checkAllTasksDeadlines();

        assertThat(task.isExpired()).isFalse();
        verify(taskRepository).save(task);
        verify(deadlineNotifier, never()).notifyExpired(any());
    }

    @Test
    @DisplayName("a task already expired and still overdue is left alone")
    void steadyStateDoesNothing() {
        Task task = task(LocalDateTime.now().minusDays(1), true);
        when(taskRepository.findAllByDeadlineIsNotNull()).thenReturn(List.of(task));

        taskService.checkAllTasksDeadlines();

        verify(taskRepository, never()).save(any());
        verify(deadlineNotifier, never()).notifyExpired(any());
    }
}
