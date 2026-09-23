package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.activity.TaskActivityRecorder;
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
 * The deadline sweep flips {@code expired} on both crossings; only the crossing into expired
 * should notify, and only after the flag is saved. Since the sweep now claims its rows rather than
 * reading them all, the database decides which tasks are candidates; these tests check what the
 * sweep does with one — direction, save-before-notify order, and that an unclaimed task is never
 * mailed. The claim itself, that {@code SKIP LOCKED} keeps two replicas off the same row, is
 * {@code DeadlineSweepClaimTest}'s to pin.
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
                mock(TaskAttachmentService.class),
                mock(TaskActivityRecorder.class), mock(BoardEventPublisher.class),
                mock(pl.myproject.kanbanproject2.task.comment.TaskCommentService.class));
    }

    private static Task task(LocalDateTime deadline, boolean expired) {
        var task = new Task();
        task.setId(1);
        task.setDeadline(deadline);
        task.setExpired(expired);
        return task;
    }

    /** What the claim would have taken: the ids, and then the rows behind them. */
    private void claimed(Task task) {
        when(taskRepository.claimTasksCrossingDeadline(any())).thenReturn(List.of(task.getId()));
        when(taskRepository.findByIdIn(List.of(task.getId()))).thenReturn(List.of(task));
    }

    @Test
    @DisplayName("a task that just passed its deadline is flagged and its assignees notified")
    void notifiesOnCrossingIntoExpired() {
        Task task = task(LocalDateTime.now().minusMinutes(5), false);
        claimed(task);

        taskService.checkAllTasksDeadlines();

        assertThat(task.isExpired()).isTrue();
        verify(taskRepository).save(task);
        verify(deadlineNotifier).notifyExpired(task);
    }

    @Test
    @DisplayName("a task whose deadline was pushed back is un-flagged without a second mail")
    void doesNotNotifyOnCrossingOutOfExpired() {
        Task task = task(LocalDateTime.now().plusDays(1), true);
        claimed(task);

        taskService.checkAllTasksDeadlines();

        assertThat(task.isExpired()).isFalse();
        verify(taskRepository).save(task);
        verify(deadlineNotifier, never()).notifyExpired(any());
    }

    @Test
    @DisplayName("a task already expired and still overdue is not claimed, so nothing happens to it")
    void steadyStateDoesNothing() {
        when(taskRepository.claimTasksCrossingDeadline(any())).thenReturn(List.of());

        taskService.checkAllTasksDeadlines();

        // The steady state is now the database's answer rather than a comparison in Java, and the
        // sweep should not so much as load a row for it - which is the point of moving it.
        verify(taskRepository, never()).findByIdIn(any());
        verify(taskRepository, never()).save(any());
        verify(deadlineNotifier, never()).notifyExpired(any());
    }

    @Test
    @DisplayName("the sweep asks for the rows the claim took, and for nothing else")
    void onlyClaimedRowsAreTouched() {
        Task mine = task(LocalDateTime.now().minusMinutes(5), false);
        claimed(mine);

        taskService.checkAllTasksDeadlines();

        // A second replica sweeping at the same instant holds the rows this one skipped; asking for
        // anything but the claimed ids is how it would mail somebody else's task a second time.
        verify(taskRepository).findByIdIn(List.of(mine.getId()));
    }
}
