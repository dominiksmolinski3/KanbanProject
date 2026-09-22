package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.activity.TaskActivityRecorder;
import pl.myproject.kanbanproject2.task.attachment.TaskAttachmentService;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryMapper;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FEAT-08's write check on the task side: every mutation entry point asks
 * {@code BoardService.requireWritable}, not just the visibility {@code findTask} already checked -
 * and a viewer, who can see the board, gets {@code VIEWER_READ_ONLY} (403) rather than a task
 * quietly not found. The read paths are deliberately not checked here; they need no write access.
 */
class TaskServiceWritabilityTest {

    private TaskRepository taskRepository;
    private BoardService boardService;
    private TaskService taskService;
    private User caller;
    private Board board;

    @BeforeEach
    void setUp() {
        var tenant = TenancyFixtures.tenant();
        caller = tenant.caller();
        board = tenant.board();
        boardService = tenant.boardService();

        taskRepository = mock(TaskRepository.class);
        var historyRepository = mock(TaskColumnHistoryRepository.class);
        when(historyRepository.findByTaskOrderByChangedAtDesc(any())).thenReturn(List.of());

        taskService = new TaskService(
                taskRepository,
                mock(UserRepository.class),
                new TaskMapper(),
                mock(UserService.class),
                historyRepository,
                mock(TaskColumnHistoryMapper.class),
                mock(ColumnRepository.class),
                mock(RowRepository.class),
                boardService,
                mock(DeadlineNotifier.class),
                mock(TaskAttachmentService.class),
                mock(TaskActivityRecorder.class), mock(BoardEventPublisher.class));

        // A viewer can still see the board (findTask's own check), but every write asks
        // requireWritable, which now refuses.
        doThrow(new GlobalException(ExceptionIdentifier.VIEWER_READ_ONLY))
                .when(boardService).requireWritable(any(User.class), any(Board.class));
    }

    private Task existingTask(int id) {
        var task = new Task();
        task.setId(id);
        task.setTitle("Task " + id);
        task.setPosition(1);
        task.setLabels(Set.of());
        task.setBoard(board);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        return task;
    }

    private static PatchTaskRequest patchTitle(String title) {
        return new PatchTaskRequest(
                JsonNullable.of(title),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                null);
    }

    private static void expectReadOnly(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.VIEWER_READ_ONLY);
    }

    @Test
    @DisplayName("a viewer cannot create a task")
    void cannotCreate() {
        expectReadOnly(() -> taskService.addTask(caller, null,
                new CreateTaskRequest("mine", null, null, null, null, null, null)));

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("a viewer cannot delete a task they can see")
    void cannotDelete() {
        existingTask(1);

        expectReadOnly(() -> taskService.deleteTask(caller, 1));

        verify(taskRepository, never()).delete(any());
    }

    @Test
    @DisplayName("a viewer cannot patch a task's title")
    void cannotPatch() {
        existingTask(1);

        expectReadOnly(() -> taskService.patchTask(caller, 1, patchTitle("renamed")));

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("a viewer cannot complete a task")
    void cannotComplete() {
        existingTask(1);

        expectReadOnly(() -> taskService.updateTaskCompletion(caller, 1, true));
    }

    @Test
    @DisplayName("a viewer cannot reorder a cell")
    void cannotReorder() {
        var first = existingTask(1);
        var second = existingTask(2);
        first.setColumn(null);
        second.setColumn(null);

        expectReadOnly(() -> taskService.reorderTasks(caller, List.of(1, 2)));

        verify(taskRepository, never()).save(any());
    }

    @Test
    @DisplayName("read paths need no write access at all")
    void readingStillWorks() {
        existingTask(1);

        assertThat(taskService.getTaskById(caller, 1)).isNotNull();
        verify(boardService, never()).requireWritable(any(), any(Board.class));
    }
}
