package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.activity.TaskActivityRecorder;
import pl.myproject.kanbanproject2.task.attachment.TaskAttachmentService;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistory;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryMapper;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import org.openapitools.jackson.nullable.JsonNullable;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That the feed is actually fed.
 *
 * <p>{@link pl.myproject.kanbanproject2.task.activity.TaskActivityServiceTest} covers what an
 * entry is and how the feed is read; this covers the half that can silently stop happening. A
 * recording call is a side effect nothing else depends on, so deleting one breaks no test, changes
 * no response, and leaves a feed that quietly stops mentioning a whole kind of event. These are
 * the assertions that notice.
 */
class TaskServiceActivityTest {

    private TaskRepository taskRepository;
    private UserRepository userRepository;
    private UserService userService;
    private TaskColumnHistoryRepository historyRepository;
    private ColumnRepository columnRepository;
    private TaskAttachmentService attachmentService;
    private TaskActivityRecorder activityRecorder;
    private TaskService taskService;

    private User caller;
    private Board board;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        userRepository = mock(UserRepository.class);
        userService = mock(UserService.class);
        historyRepository = mock(TaskColumnHistoryRepository.class);
        columnRepository = mock(ColumnRepository.class);
        attachmentService = mock(TaskAttachmentService.class);
        activityRecorder = mock(TaskActivityRecorder.class);

        var tenant = TenancyFixtures.tenant();
        caller = tenant.caller();
        board = tenant.board();

        taskService = new TaskService(
                taskRepository,
                userRepository,
                new TaskMapper(),
                userService,
                historyRepository,
                mock(TaskColumnHistoryMapper.class),
                columnRepository,
                mock(RowRepository.class),
                tenant.boardService(),
                mock(DeadlineNotifier.class),
                attachmentService,
                activityRecorder);

        when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
        when(taskRepository.findMaxPosition(any(), any(), any())).thenReturn(Optional.empty());
        when(historyRepository.save(any(TaskColumnHistory.class)))
                .thenAnswer(call -> call.getArgument(0));
        when(historyRepository.findByTaskOrderByChangedAtDesc(any())).thenReturn(List.of());
        when(userService.checkWipStatus(any())).thenReturn(true);
    }

    private Task existingTask(int id) {
        var task = new Task();
        task.setId(id);
        task.setTitle("Ship the release");
        task.setBoard(board);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        return task;
    }

    @Test
    @DisplayName("creating a task is recorded")
    void creatingIsRecorded() {
        taskService.addTask(caller, null,
                new CreateTaskRequest("New card", null, null, null, null, null, null));

        verify(activityRecorder).created(any(User.class), any(Task.class));
    }

    @Test
    @DisplayName("assigning and unassigning are recorded, each naming the person")
    void assignmentIsRecorded() {
        var task = existingTask(5);
        var assignee = TenancyFixtures.user(2);
        board.addMember(assignee);
        when(userRepository.findById(2)).thenReturn(Optional.of(assignee));

        taskService.assignUserToTask(caller, 5, 2);
        taskService.removeUserFromTask(caller, 5, 2);

        var order = inOrder(activityRecorder);
        order.verify(activityRecorder).assigned(caller, task, assignee);
        order.verify(activityRecorder).unassigned(caller, task, assignee);
    }

    @Test
    @DisplayName("completing and reopening are recorded, and re-ticking a done task is not")
    void completionIsRecordedOnlyWhenItChanges() {
        var task = existingTask(5);

        taskService.updateTaskCompletion(caller, 5, true);
        verify(activityRecorder).completionChanged(caller, task, true);

        // Already complete. A request that changes nothing is not an event, and a feed that
        // records it fills with entries nobody performed.
        taskService.updateTaskCompletion(caller, 5, true);
        verify(activityRecorder).completionChanged(caller, task, true);

        taskService.updateTaskCompletion(caller, 5, false);
        verify(activityRecorder).completionChanged(caller, task, false);
    }

    /**
     * The order is the assertion. The entry is written while the task is still there - so it can
     * copy the title - and the remaining entries are detached before the row goes, because there
     * is no cascade on that column and the delete would otherwise fail on the foreign key.
     */
    @Test
    @DisplayName("a deletion is recorded before the task goes, and its entries are detached rather than deleted")
    void deletionIsRecordedThenDetached() {
        var task = existingTask(5);

        taskService.deleteTask(caller, 5);

        var order = inOrder(activityRecorder, taskRepository);
        order.verify(activityRecorder).deleted(caller, task);
        order.verify(activityRecorder).detachFrom(task);
        order.verify(taskRepository).delete(task);
    }

    private Column column(int id, String name) {
        var column = new Column();
        column.setId(id);
        column.setName(name);
        column.setBoard(board);
        when(columnRepository.findById(id)).thenReturn(Optional.of(column));
        return column;
    }

    /**
     * The move is the one event with two records, and both are written in the same three lines of
     * {@code moveToColumn}. They are not duplicates: {@code task_column_history} is the interval
     * series the task panel folds into time-per-column and has never recorded who did it. Writing
     * them together is the only thing keeping them from drifting.
     */
    @Test
    @DisplayName("a move writes a history row and a feed entry, and the entry names the column")
    void aMoveIsRecordedBesideTheHistoryRow() {
        var task = existingTask(5);
        task.setColumn(column(2, "Backlog"));
        column(3, "In Progress");

        taskService.patchTask(caller, 5, new PatchTaskRequest(
                null, null, null, null, null,
                JsonNullable.of(new IdRef(3)), null, null));

        verify(historyRepository).save(any(TaskColumnHistory.class));
        verify(activityRecorder).moved(caller, task, "In Progress");
    }

    @Test
    @DisplayName("patching a task into the column it is already in records nothing at all")
    void aNonMoveRecordsNothing() {
        var task = existingTask(5);
        task.setColumn(column(3, "In Progress"));

        taskService.patchTask(caller, 5, new PatchTaskRequest(
                null, null, null, null, null,
                JsonNullable.of(new IdRef(3)), null, null));

        verify(activityRecorder, never()).moved(any(), any(), any());
        verify(historyRepository, never()).save(any(TaskColumnHistory.class));
    }
}
