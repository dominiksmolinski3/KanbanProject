package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.BoardColumn;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                activityRecorder, mock(BoardEventPublisher.class),
                mock(pl.myproject.kanbanproject2.task.comment.TaskCommentService.class));

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
    @DisplayName("assigning someone already on the task changes nothing, records nothing and skips the WIP check")
    void reassigningAnAssigneeIsANoOp() {
        var task = existingTask(5);
        var assignee = TenancyFixtures.user(2);
        board.addMember(assignee);
        task.getUsers().add(assignee);
        assignee.getTasks().add(task);
        when(userRepository.findById(2)).thenReturn(Optional.of(assignee));
        when(userService.checkWipStatus(2)).thenReturn(false);

        assertThatCode(() -> taskService.assignUserToTask(caller, 5, 2))
                .as("the WIP count already includes this task, so a repeat would be refused for a no-op")
                .doesNotThrowAnyException();

        assertThat(task.getUsers()).containsExactly(assignee);
        verify(activityRecorder, never()).assigned(any(), any(), any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("unassigning someone who is not on the task is a 404 that records nothing, even for a member")
    void unassigningANonAssigneeRecordsNothing() {
        existingTask(5);
        var stranger = TenancyFixtures.user(3);
        var member = TenancyFixtures.user(4);
        board.addMember(member);
        when(userRepository.findById(3)).thenReturn(Optional.of(stranger));
        when(userRepository.findById(4)).thenReturn(Optional.of(member));

        for (var userId : List.of(3, 4, 99)) {
            assertThatThrownBy(() -> taskService.removeUserFromTask(caller, 5, userId))
                    .isInstanceOfSatisfying(GlobalException.class, e ->
                            assertThat(e.getIdentifier()).isEqualTo(ExceptionIdentifier.USER_NOT_FOUND));
        }

        verify(activityRecorder, never()).unassigned(any(), any(), any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("completing and reopening are recorded, and re-ticking a done task is not")
    void completionIsRecordedOnlyWhenItChanges() {
        var task = existingTask(5);

        taskService.updateTaskCompletion(caller, 5, true);
        verify(activityRecorder).completionChanged(caller, task, true);

        taskService.updateTaskCompletion(caller, 5, true);
        verify(activityRecorder).completionChanged(caller, task, true);

        taskService.updateTaskCompletion(caller, 5, false);
        verify(activityRecorder).completionChanged(caller, task, false);
    }

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

    private BoardColumn column(int id, String name) {
        var column = new BoardColumn();
        column.setId(id);
        column.setName(name);
        column.setBoard(board);
        when(columnRepository.findById(id)).thenReturn(Optional.of(column));
        return column;
    }

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
