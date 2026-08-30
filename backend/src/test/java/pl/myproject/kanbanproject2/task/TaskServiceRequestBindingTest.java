package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.openapitools.jackson.nullable.JsonNullable;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.Row;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryMapper;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Covers what the request records changed: which fields a client can reach, and what an explicitly
 * null one now does.
 */
class TaskServiceRequestBindingTest {

    private TaskRepository taskRepository;
    private ColumnRepository columnRepository;
    private RowRepository rowRepository;
    private TaskColumnHistoryRepository historyRepository;
    private TaskService taskService;
    private User caller;
    private Board board;

    @BeforeEach
    void setUp() {
        var tenant = TenancyFixtures.tenant();
        caller = tenant.caller();
        board = tenant.board();
        taskRepository = Mockito.mock(TaskRepository.class);
        columnRepository = Mockito.mock(ColumnRepository.class);
        rowRepository = Mockito.mock(RowRepository.class);
        historyRepository = Mockito.mock(TaskColumnHistoryRepository.class);

        when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
        when(historyRepository.findByTaskOrderByChangedAtDesc(any())).thenReturn(List.of());

        taskService = new TaskService(
                taskRepository,
                Mockito.mock(UserRepository.class),
                new TaskMapper(),
                Mockito.mock(UserService.class),
                historyRepository,
                Mockito.mock(TaskColumnHistoryMapper.class),
                columnRepository,
                rowRepository,
                tenant.boardService());
    }

    @Test
    @DisplayName("an explicitly null row detaches the task from its swimlane")
    void clearsTheRowWhenItIsSentAsNull() {
        Task task = existingTask(1);
        task.setRow(row(9));

        TaskDto patched = taskService.patchTask(caller, 1, patch(b -> b.row = JsonNullable.of(null)));

        assertThat(patched.rowId()).isNull();
    }

    @Test
    @DisplayName("a row left out of the body leaves the swimlane alone")
    void leavesTheRowAloneWhenItIsAbsent() {
        Task task = existingTask(1);
        task.setRow(row(9));

        TaskDto patched = taskService.patchTask(caller, 1, patch(b -> b.title = JsonNullable.of("renamed")));

        assertThat(patched.rowId()).isEqualTo(9);
        assertThat(patched.title()).isEqualTo("renamed");
    }

    @Test
    @DisplayName("clearing the deadline clears the expired flag the scheduler can no longer reach")
    void clearingTheDeadlineClearsExpired() {
        Task task = existingTask(1);
        task.setDeadline(LocalDateTime.now().minusDays(2));
        task.setExpired(true);

        TaskDto patched = taskService.patchTask(caller, 1, patch(b -> b.deadline = JsonNullable.of(null)));

        assertThat(patched.deadline()).isNull();
        assertThat(patched.expired()).isFalse();
    }

    @Test
    @DisplayName("an unknown column id is a 404, not a foreign-key violation at flush time")
    void unknownColumnIsNotFound() {
        existingTask(1);
        when(columnRepository.findById(404)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.patchTask(caller, 1, patch(b -> b.column = JsonNullable.of(new IdRef(404)))))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.COLUMN_NOT_FOUND);
    }

    @Test
    @DisplayName("a blank title is rejected rather than written over the existing one")
    void rejectsABlankTitle() {
        existingTask(1);

        assertThatThrownBy(() -> taskService.patchTask(caller, 1, patch(b -> b.title = JsonNullable.of("   "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a created task starts uncompleted with a generated id, whatever the caller sends")
    void createIgnoresFieldsTheRequestCannotCarry() {
        when(columnRepository.findById(2)).thenReturn(Optional.of(column(2)));

        TaskDto created = taskService.addTask(caller, null, new CreateTaskRequest(
                "new task", null, null, null, Set.of("bug"), new IdRef(2), null));

        // CreateTaskRequest has no id and no completed component, so neither can arrive off the wire.
        assertThat(created.id()).isNull();
        assertThat(created.completed()).isFalse();
        // Where the position comes from is BoardDataIntegrityTest's subject; that it is set at all
        // matters here, because getAllTasks sorts on it.
        assertThat(created.position()).isEqualTo(1);
        assertThat(created.columnId()).isEqualTo(2);
        assertThat(created.labels()).containsExactly("bug");
    }

    private Task existingTask(int id) {
        Task task = new Task();
        task.setId(id);
        task.setTitle("original");
        task.setPosition(1);
        task.setBoard(board);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        return task;
    }

    private Column column(int id) {
        Column column = new Column();
        column.setId(id);
        column.setName("Doing");
        column.setBoard(board);
        return column;
    }

    private Row row(int id) {
        Row row = new Row();
        row.setId(id);
        row.setName("Team A");
        row.setBoard(board);
        return row;
    }

    /** Keeps the tests readable: every component defaults to undefined, the test names the one it sets. */
    private static PatchTaskRequest patch(java.util.function.Consumer<PatchBuilder> customiser) {
        PatchBuilder builder = new PatchBuilder();
        customiser.accept(builder);
        return new PatchTaskRequest(builder.title, builder.description, builder.position,
                builder.deadline, builder.labels, builder.column, builder.row);
    }

    private static final class PatchBuilder {
        JsonNullable<String> title = JsonNullable.undefined();
        JsonNullable<String> description = JsonNullable.undefined();
        JsonNullable<Integer> position = JsonNullable.undefined();
        JsonNullable<LocalDateTime> deadline = JsonNullable.undefined();
        JsonNullable<Set<String>> labels = JsonNullable.undefined();
        JsonNullable<IdRef> column = JsonNullable.undefined();
        JsonNullable<IdRef> row = JsonNullable.undefined();
    }
}
