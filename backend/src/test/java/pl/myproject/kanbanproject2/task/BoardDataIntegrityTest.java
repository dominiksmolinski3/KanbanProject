package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.data.repository.query.parser.PartTree;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.Row;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.subtask.SubTask;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistory;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three ways the board could hand back data that is wrong rather than missing: positions that
 * collide, a column-history report whose numbers depend on how a tie sorts, and a task graph that
 * a single bad row turns into a {@link StackOverflowError}.
 */
class BoardDataIntegrityTest {

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
        // The next position is now a MAX aggregate rather than a fold over fetched rows, so
        // these stub the aggregate. What each test asserts is unchanged: the number handed out.
        // It is keyed by ids rather than by the entities, because a null cell has to reach the
        // query as a null id it can test for - see TaskRepository.findMaxPosition.
        when(taskRepository.findMaxPosition(any(), any(), any())).thenReturn(Optional.empty());
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
                tenant.boardService(),
                Mockito.mock(DeadlineNotifier.class),
                Mockito.mock(TaskAttachmentService.class));
    }

    @Nested
    @DisplayName("positions")
    class Positions {

        @Test
        @DisplayName("a new task is numbered from the cell it lands in, not from the table")
        void numbersWithinTheCell() {
            Column column = column(3);
            Row row = row(8);
            when(columnRepository.findById(3)).thenReturn(Optional.of(column));
            when(rowRepository.findById(8)).thenReturn(Optional.of(row));
            when(taskRepository.findMaxPosition(board.getId(), 3, 8)).thenReturn(Optional.of(2));

            TaskDto created = taskService.addTask(caller, null,
                    new CreateTaskRequest("Third", null, null, null, null, new IdRef(3), new IdRef(8)));

            assertThat(created.position()).isEqualTo(3);
        }

        @Test
        @DisplayName("an empty cell starts at one however many tasks the board already holds")
        void startsAtOneInAnEmptyCell() {
            Column column = column(3);
            when(columnRepository.findById(3)).thenReturn(Optional.of(column));
            when(taskRepository.findMaxPosition(board.getId(), 3, null)).thenReturn(Optional.empty());

            TaskDto created = taskService.addTask(caller, null,
                    new CreateTaskRequest("First here", null, null, null, null, new IdRef(3), null));

            assertThat(created.position()).isEqualTo(1);
            // The old count()-based number is what made a delete anywhere on the board collide
            // with the next create here. Nor is the cell's own list fetched any more - the
            // database answers the max, rather than handing over rows to fold in Java.
            verify(taskRepository, never()).count();
            verify(taskRepository, never()).findByBoardAndColumnAndRow(any(), any(), any());
        }

        @Test
        @DisplayName("a position already taken in the cell is not handed out again")
        void doesNotReuseAPositionAfterADelete() {
            Column column = column(3);
            when(columnRepository.findById(3)).thenReturn(Optional.of(column));
            // Positions 1 and 2 were deleted; 3 is still in use. count() would answer 2.
            when(taskRepository.findMaxPosition(board.getId(), 3, null)).thenReturn(Optional.of(3));

            TaskDto created = taskService.addTask(caller, null,
                    new CreateTaskRequest("Next", null, null, null, null, new IdRef(3), null));

            assertThat(created.position()).isEqualTo(4);
        }

        @Test
        @DisplayName("a legacy task with no position sorts last instead of failing the listing")
        void toleratesANullPositionWhenListing() {
            when(taskRepository.findByBoardOrderByIdAsc(board))
                    .thenReturn(List.of(taskAt(1, null), taskAt(2, 5), taskAt(3, 1)));

            List<TaskDto> all = taskService.getAllTasks(caller, null);

            assertThat(all).extracting(TaskDto::id).containsExactly(3, 2, 1);
        }
    }

    @Nested
    @DisplayName("column history")
    class ColumnHistory {

        @Test
        @DisplayName("a move records the arrival only, so the report has no tie to break")
        void recordsOnlyTheArrival() {
            Task task = existingTask(1);
            task.setColumn(column(3));
            when(columnRepository.findById(4)).thenReturn(Optional.of(column(4)));

            taskService.patchTask(caller, 1, patchColumn(4));

            ArgumentCaptor<TaskColumnHistory> written = ArgumentCaptor.forClass(TaskColumnHistory.class);
            verify(historyRepository, times(1)).save(written.capture());
            assertThat(written.getValue().getColumn().getId()).isEqualTo(4);
        }

        @Test
        @DisplayName("taking a task off the board records nothing, because nothing arrived")
        void recordsNothingWhenTheColumnIsCleared() {
            Task task = existingTask(1);
            task.setColumn(column(3));

            taskService.patchTask(caller, 1, patchColumn(null));

            verify(historyRepository, never()).save(any());
        }

        @Test
        @DisplayName("a move that changes nothing writes nothing")
        void recordsNothingWhenTheColumnIsUnchanged() {
            Task task = existingTask(1);
            task.setColumn(column(3));
            when(columnRepository.findById(3)).thenReturn(Optional.of(column(3)));

            taskService.patchTask(caller, 1, patchColumn(3));

            verify(historyRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the task graph")
    class TaskGraph {

        @Test
        @DisplayName("a cycle already in the data does not blow the stack on the next assignment")
        void survivesACycleInTheStoredGraph() {
            Task first = existingTask(1);
            Task second = existingTask(2);
            first.getChildTasks().add(second);
            second.getChildTasks().add(first);

            existingTask(3);

            TaskDto patched = taskService.assignParentTask(caller, 1, 3);

            assertThat(patched.parentTaskId()).isEqualTo(3);
        }

        @Test
        @DisplayName("un-completing still cascades, and still terminates, through a cycle")
        void cascadesUncompletionThroughACycle() {
            Task first = existingTask(1);
            Task second = existingTask(2);
            first.setCompleted(true);
            second.setCompleted(true);
            first.getChildTasks().add(second);
            second.getChildTasks().add(first);

            taskService.updateTaskCompletion(caller, 1, false);

            assertThat(first.isCompleted()).isFalse();
            assertThat(second.isCompleted()).isFalse();
        }

        @Test
        @DisplayName("un-completing a parent still un-completes the whole chain below it")
        void cascadesDownTheWholeChain() {
            Task parent = existingTask(1);
            Task child = existingTask(2);
            Task grandchild = existingTask(3);
            parent.setCompleted(true);
            child.setCompleted(true);
            grandchild.setCompleted(true);
            parent.getChildTasks().add(child);
            child.getChildTasks().add(grandchild);

            taskService.updateTaskCompletion(caller, 1, false);

            assertThat(child.isCompleted()).isFalse();
            assertThat(grandchild.isCompleted()).isFalse();
        }
    }

    @Test
    @DisplayName("the scoped queries the positions are read from resolve against the entities")
    void derivedQueriesResolve() {
        // Nothing else in the suite boots a JPA context, so a property that stopped existing would
        // otherwise surface at runtime. PartTree is the same parser Spring Data derives them with.
        assertThat(new PartTree("findByBoardAndColumnAndRow", Task.class).getParts()).hasSize(3);
        assertThat(new PartTree("findByBoardOrderByIdAsc", Task.class).getParts()).hasSize(1);
        // findByTask is gone: the subtask position is a MAX aggregate now, and it was that
        // method's only caller. The aggregate is guarded by QueryStringsResolveTest instead.
        assertThat(new PartTree("findByTaskBoardOrderByIdAsc", SubTask.class).getParts()).hasSize(1);
        assertThat(new PartTree("findByBoardOrderByPositionAsc", Column.class).getParts()).hasSize(1);
        assertThat(new PartTree("findByBoardOrderByPositionAsc", Row.class).getParts()).hasSize(1);
    }

    private PatchTaskRequest patchColumn(Integer columnId) {
        return new PatchTaskRequest(
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.undefined(),
                JsonNullable.of(columnId == null ? null : new IdRef(columnId)),
                JsonNullable.undefined(),
                null);
    }

    private Task existingTask(Integer id) {
        Task task = taskAt(id, 1);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        return task;
    }

    private Task taskAt(Integer id, Integer position) {
        Task task = new Task();
        task.setId(id);
        task.setTitle("Task " + id);
        task.setPosition(position);
        task.setLabels(Set.of());
        task.setBoard(board);
        return task;
    }

    private Column column(Integer id) {
        Column column = new Column();
        column.setId(id);
        column.setName("Column " + id);
        column.setBoard(board);
        return column;
    }

    private Row row(Integer id) {
        Row row = new Row();
        row.setId(id);
        row.setName("Row " + id);
        row.setBoard(board);
        return row;
    }
}
