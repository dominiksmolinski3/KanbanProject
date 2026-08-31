package pl.myproject.kanbanproject2.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.row.Row;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Why a batch route exists at all, stated as tests.
 *
 * <p>One PATCH per card was merely wasteful while a lost update was silent. Since the tasks gained
 * a {@code @Version} it is worse than that: a card somebody else moved makes one call a 409, the
 * calls before it stay applied, and the board is left in an order nobody asked for. The batch is
 * one transaction, so the interesting assertions here are about what it refuses rather than about
 * what it renumbers - a request that cannot mean anything must not half-apply either.
 *
 * <p>What these cannot show is the rollback itself: a mocked repository has no transaction to roll
 * back. That the boundary exists is a property of {@code @Transactional} on the service, and the
 * 409 the client sees is pinned by {@code OptimisticLockConflictTest}.
 */
class ReorderTasksTest {

    /** Somebody else's board: a different id and a different owner, so it is not visible here. */
    private final Board otherBoard = TenancyFixtures.board(99, TenancyFixtures.user(2));
    private final Map<Integer, Task> stored = new HashMap<>();
    private TaskRepository taskRepository;
    private TaskService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        when(taskRepository.findById(any()))
                .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
        service = TaskServiceTestSupport.withRepository(taskRepository);
    }

    private Column column(int id) {
        var column = new Column();
        column.setId(id);
        column.setBoard(TaskServiceTestSupport.board());
        return column;
    }

    private Row row(int id) {
        var row = new Row();
        row.setId(id);
        row.setBoard(TaskServiceTestSupport.board());
        return row;
    }

    private Task task(int id, Column column, Row row, int position) {
        var task = new Task();
        task.setId(id);
        task.setBoard(TaskServiceTestSupport.board());
        task.setColumn(column);
        task.setRow(row);
        task.setPosition(position);
        stored.put(id, task);
        return task;
    }

    @Test
    @DisplayName("position becomes the index in the list, over the whole cell in one call")
    void positionsFollowTheListOrder() {
        var todo = column(1);
        var lane = row(1);
        task(10, todo, lane, 0);
        task(11, todo, lane, 1);
        task(12, todo, lane, 2);

        var reordered = service.reorderTasks(TaskServiceTestSupport.caller(), List.of(12, 10, 11));

        assertThat(reordered).extracting(TaskDto::id).containsExactly(12, 10, 11);
        assertThat(reordered).extracting(TaskDto::position).containsExactly(0, 1, 2);
        assertThat(stored.get(12).getPosition()).isZero();
        assertThat(stored.get(11).getPosition()).isEqualTo(2);
    }

    @Test
    @DisplayName("a cell with no swimlane reorders like any other - null is a cell, not a wildcard")
    void aCellWithNoSwimlaneReorders() {
        var todo = column(1);
        task(20, todo, null, 0);
        task(21, todo, null, 1);

        var reordered = service.reorderTasks(TaskServiceTestSupport.caller(), List.of(21, 20));

        assertThat(reordered).extracting(TaskDto::id).containsExactly(21, 20);
        assertThat(stored.get(21).getPosition()).isZero();
    }

    @Test
    @DisplayName("tasks in two different columns are refused rather than numbered against each other")
    void twoColumnsAreRefused() {
        var todo = column(1);
        var doing = column(2);
        var lane = row(1);
        task(30, todo, lane, 0);
        task(31, doing, lane, 0);

        assertThatThrownBy(() -> service.reorderTasks(TaskServiceTestSupport.caller(), List.of(30, 31)))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.INVALID_REORDER);
    }

    @Test
    @DisplayName("the same column but two swimlanes is refused too - a cell is both, not just one")
    void twoSwimlanesAreRefused() {
        var todo = column(1);
        task(40, todo, row(1), 0);
        task(41, todo, row(2), 0);

        assertThatThrownBy(() -> service.reorderTasks(TaskServiceTestSupport.caller(), List.of(40, 41)))
                .isInstanceOf(GlobalException.class);
    }

    @Test
    @DisplayName("a task with no column and one with a column are different cells")
    void anUnplacedTaskIsItsOwnCell() {
        var todo = column(1);
        task(50, todo, null, 0);
        task(51, null, null, 0);

        assertThatThrownBy(() -> service.reorderTasks(TaskServiceTestSupport.caller(), List.of(50, 51)))
                .isInstanceOf(GlobalException.class);
    }

    @Test
    @DisplayName("a repeated id is refused, because it asks for two positions at once")
    void aRepeatedIdIsRefused() {
        var todo = column(1);
        task(60, todo, null, 0);
        task(61, todo, null, 1);

        assertThatThrownBy(() ->
                service.reorderTasks(TaskServiceTestSupport.caller(), List.of(60, 61, 60)))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.INVALID_REORDER);
    }

    @Test
    @DisplayName("a duplicate is caught before anything is looked up, let alone written")
    void aRepeatedIdWritesNothing() {
        var todo = column(1);
        var first = task(70, todo, null, 0);
        task(71, todo, null, 1);

        assertThatThrownBy(() ->
                service.reorderTasks(TaskServiceTestSupport.caller(), List.of(70, 71, 71)))
                .isInstanceOf(GlobalException.class);

        assertThat(first.getPosition()).as("nothing renumbered").isZero();
        org.mockito.Mockito.verify(taskRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("an unknown id is a 404, and the tasks that were valid keep their old order")
    void anUnknownIdIsNotFound() {
        var todo = column(1);
        var first = task(80, todo, null, 0);

        assertThatThrownBy(() -> service.reorderTasks(TaskServiceTestSupport.caller(), List.of(80, 999)))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.TASK_NOT_FOUND);

        // Every id is resolved before any position is written, so a bad one in the middle of the
        // list cannot leave the first half renumbered.
        assertThat(first.getPosition()).isZero();
        org.mockito.Mockito.verify(taskRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("a task on another board answers 404, the same as one that does not exist")
    void aTaskOnAnotherBoardIsNotFound() {
        var todo = column(1);
        task(90, todo, null, 0);

        var elsewhere = new Task();
        elsewhere.setId(91);
        elsewhere.setBoard(otherBoard);
        elsewhere.setColumn(todo);
        stored.put(91, elsewhere);

        assertThatThrownBy(() -> service.reorderTasks(TaskServiceTestSupport.caller(), List.of(90, 91)))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.TASK_NOT_FOUND);
    }

    @Test
    @DisplayName("one id is a legitimate reorder and settles it at position 0")
    void oneIdIsAllowed() {
        var todo = column(1);
        task(100, todo, null, 4);

        assertThat(service.reorderTasks(TaskServiceTestSupport.caller(), List.of(100)))
                .extracting(TaskDto::position)
                .containsExactly(0);
    }
}
