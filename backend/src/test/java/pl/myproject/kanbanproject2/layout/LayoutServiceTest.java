package pl.myproject.kanbanproject2.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnDto;
import pl.myproject.kanbanproject2.layout.column.ColumnMapper;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.column.ColumnService;
import pl.myproject.kanbanproject2.layout.column.CreateColumnRequest;
import pl.myproject.kanbanproject2.layout.row.CreateRowRequest;
import pl.myproject.kanbanproject2.layout.row.Row;
import pl.myproject.kanbanproject2.layout.row.RowDto;
import pl.myproject.kanbanproject2.layout.row.RowMapper;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.layout.row.RowService;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskMapper;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.TaskService;
import pl.myproject.kanbanproject2.user.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two layout services, which between them were the least covered code in the project.
 *
 * <p>They are also where three rules that were argued about elsewhere have to hold again, and
 * nothing was checking that they did: the next position comes from the highest one in use rather
 * than from a row count, a patch treats {@code null} as "leave it alone" rather than as "clear it",
 * and an object on somebody else's board answers 404 rather than 403.
 *
 * <p>The delete paths are covered by {@code DeleteDetachesReferencesTest}, which is about the
 * foreign keys they used to fail on; these are about the rest of each service.
 */
class LayoutServiceTest {

    private static final TenancyFixtures.Tenant TENANT = TenancyFixtures.tenant();
    private static final Board BOARD = TENANT.board();
    private static final User CALLER = TENANT.caller();
    /** A different id and a different owner, so nothing on it is visible to CALLER. */
    private static final Board OTHER_BOARD = TenancyFixtures.board(99, TenancyFixtures.user(2));

    @Nested
    @DisplayName("stages")
    class Columns {

        private final ColumnRepository repository = mock(ColumnRepository.class);
        private final ColumnService service = new ColumnService(
                repository, new ColumnMapper(new TaskMapper()),
                mock(TaskService.class), TENANT.boardService());

        private Column column(int id, Board board) {
            var column = new Column();
            column.setId(id);
            column.setBoard(board);
            column.setName("To Do");
            column.setPosition(0);
            column.setWipLimit(3);
            column.setTasks(new ArrayList<>());
            return column;
        }

        @Test
        @DisplayName("listing asks for this board's stages in position order, not for every stage")
        void listingIsScopedAndOrdered() {
            when(repository.findByBoardOrderByPositionAsc(BOARD)).thenReturn(List.of(column(1, BOARD)));

            assertThat(service.getAllColumns(CALLER, null)).extracting(ColumnDto::name).containsExactly("To Do");

            // findAll() would hand one caller every board in the deployment.
            verify(repository, never()).findAll();
        }

        @Test
        @DisplayName("a new stage lands after the highest position in use, not at count + 1")
        void nextPositionComesFromTheHighestInUse() {
            // Seven stages of which four were deleted: a count would say 4 and collide with 7.
            when(repository.findMaxPosition(BOARD)).thenReturn(Optional.of(7));
            when(repository.save(any(Column.class))).thenAnswer(call -> call.getArgument(0));

            assertThat(service.addNewColumn(CALLER, null, new CreateColumnRequest("Review", null, 2)).position())
                    .isEqualTo(8);
        }

        @Test
        @DisplayName("the first stage on an empty board is position 1")
        void theFirstStageIsPositionOne() {
            when(repository.findMaxPosition(BOARD)).thenReturn(Optional.empty());
            when(repository.save(any(Column.class))).thenAnswer(call -> call.getArgument(0));

            assertThat(service.addNewColumn(CALLER, null, new CreateColumnRequest("Review", null, null)).position())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an explicit position is taken as given rather than recomputed")
        void anExplicitPositionWins() {
            when(repository.save(any(Column.class))).thenAnswer(call -> call.getArgument(0));

            assertThat(service.addNewColumn(CALLER, null, new CreateColumnRequest("Review", 2, null)).position())
                    .isEqualTo(2);
            verify(repository, never()).findMaxPosition(any());
        }

        @Test
        @DisplayName("a patch changes the fields it names and leaves the rest alone")
        void aPatchOnlyTouchesWhatItNames() {
            var existing = column(1, BOARD);
            when(repository.findById(1)).thenReturn(Optional.of(existing));
            when(repository.save(any(Column.class))).thenAnswer(call -> call.getArgument(0));

            var patched = service.patchColumn(CALLER, new ColumnDto(null, "Doing", null, null, null), 1);

            assertThat(patched.name()).isEqualTo("Doing");
            // null means "leave it" rather than "clear it" - the whole reason a PATCH is not a PUT.
            assertThat(patched.wipLimit()).isEqualTo(3);
            assertThat(patched.position()).isZero();
        }

        @Test
        @DisplayName("a stage on another board answers 404, not 403")
        void aStageOnAnotherBoardIsNotFound() {
            when(repository.findById(5)).thenReturn(Optional.of(column(5, OTHER_BOARD)));

            assertThatThrownBy(() -> service.getColumnById(CALLER, 5))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    // 403 would confirm the id is in use, which is enough to map a board by
                    // walking ids and reading status codes.
                    .isEqualTo(ExceptionIdentifier.COLUMN_NOT_FOUND);
        }

        @Test
        @DisplayName("an id that does not exist answers exactly the same way")
        void anUnknownStageAnswersTheSame() {
            when(repository.findById(6)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getColumnById(CALLER, 6))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.COLUMN_NOT_FOUND);
        }

        @Test
        @DisplayName("repositioning goes through the same check, so it cannot reach another board")
        void repositioningIsScopedToo() {
            when(repository.findById(5)).thenReturn(Optional.of(column(5, OTHER_BOARD)));

            assertThatThrownBy(() -> service.updateColumnPosition(CALLER, 5, 2))
                    .isInstanceOf(GlobalException.class);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("repositioning a stage on this board writes the new position")
        void repositioningWritesThePosition() {
            when(repository.findById(1)).thenReturn(Optional.of(column(1, BOARD)));
            when(repository.save(any(Column.class))).thenAnswer(call -> call.getArgument(0));

            assertThat(service.updateColumnPosition(CALLER, 1, 4).position()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("swimlanes")
    class Rows {

        private final RowRepository repository = mock(RowRepository.class);
        private final TaskRepository taskRepository = mock(TaskRepository.class);
        private final RowService service = new RowService(
                repository, new RowMapper(new TaskMapper()), taskRepository, TENANT.boardService());

        private Row row(int id, Board board) {
            var row = new Row();
            row.setId(id);
            row.setBoard(board);
            row.setName("Features");
            row.setPosition(0);
            row.setWipLimit(5);
            row.setTasks(new ArrayList<>());
            return row;
        }

        @Test
        @DisplayName("listing is scoped to the board and ordered by position")
        void listingIsScopedAndOrdered() {
            when(repository.findByBoardOrderByPositionAsc(BOARD)).thenReturn(List.of(row(1, BOARD)));

            assertThat(service.getAllRows(CALLER, null)).extracting(RowDto::name).containsExactly("Features");
            verify(repository, never()).findAll();
        }

        @Test
        @DisplayName("a new swimlane lands after the highest position in use")
        void nextPositionComesFromTheHighestInUse() {
            when(repository.findMaxPosition(BOARD)).thenReturn(Optional.of(3));
            when(repository.save(any(Row.class))).thenAnswer(call -> call.getArgument(0));

            assertThat(service.createRow(CALLER, null, new CreateRowRequest("Bugs", null, 2)).position())
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("the first swimlane on an empty board is position 1")
        void theFirstSwimlaneIsPositionOne() {
            when(repository.findMaxPosition(BOARD)).thenReturn(Optional.empty());
            when(repository.save(any(Row.class))).thenAnswer(call -> call.getArgument(0));

            assertThat(service.createRow(CALLER, null, new CreateRowRequest("Bugs", null, null)).position())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a patch changes the fields it names and leaves the rest alone")
        void aPatchOnlyTouchesWhatItNames() {
            var existing = row(1, BOARD);
            when(repository.findById(1)).thenReturn(Optional.of(existing));
            when(repository.save(any(Row.class))).thenAnswer(call -> call.getArgument(0));

            var patched = service.patchRow(CALLER, new RowDto(null, null, 6, null, null), 1);

            assertThat(patched.position()).isEqualTo(6);
            assertThat(patched.name()).isEqualTo("Features");
            assertThat(patched.wipLimit()).isEqualTo(5);
        }

        @Test
        @DisplayName("a swimlane on another board answers 404, not 403")
        void aSwimlaneOnAnotherBoardIsNotFound() {
            when(repository.findById(5)).thenReturn(Optional.of(row(5, OTHER_BOARD)));

            assertThatThrownBy(() -> service.getRowById(CALLER, 5))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ROW_NOT_FOUND);
        }

        @Test
        @DisplayName("deleting takes the tasks out of the swimlane rather than deleting them with it")
        void deletingDetachesRatherThanCascades() {
            var swimlane = row(1, BOARD);
            var task = new Task();
            task.setId(11);
            task.setBoard(BOARD);
            task.setRow(swimlane);
            swimlane.setTasks(new ArrayList<>(List.of(task)));
            when(repository.findById(1)).thenReturn(Optional.of(swimlane));

            service.deleteRow(CALLER, 1);

            // A task outlives its swimlane: the column is what puts it on the board.
            assertThat(task.getRow()).isNull();
            verify(taskRepository).save(task);
            verify(repository).delete(swimlane);
        }

        @Test
        @DisplayName("repositioning is scoped, so it cannot reach another board either")
        void repositioningIsScopedToo() {
            when(repository.findById(5)).thenReturn(Optional.of(row(5, OTHER_BOARD)));

            assertThatThrownBy(() -> service.updateRowPosition(CALLER, 5, 2))
                    .isInstanceOf(GlobalException.class);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("the mapper carries a swimlane's tasks, and answers null for no swimlane at all")
        void theMapperHandlesTasksAndNull() {
            var mapper = new RowMapper(new TaskMapper());
            var swimlane = row(1, BOARD);
            var task = new Task();
            task.setId(11);
            swimlane.setTasks(new ArrayList<>(List.of(task)));

            assertThat(mapper.apply(swimlane).taskDTO()).hasSize(1);
            assertThat(mapper.apply(null)).isNull();
            assertThat(mapper.toResponseDto(null)).isNull();
            assertThat(mapper.toResponseDto(swimlane).name()).isEqualTo("Features");
        }
    }
}
