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
import pl.myproject.kanbanproject2.layout.row.Row;
import pl.myproject.kanbanproject2.layout.row.RowDto;
import pl.myproject.kanbanproject2.layout.row.RowMapper;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.layout.row.RowService;
import pl.myproject.kanbanproject2.task.TaskMapper;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.TaskService;
import pl.myproject.kanbanproject2.user.User;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dragging a stage or a swimlane, as one call rather than one call per item.
 *
 * <p>The layout reorders have the same defect the task one does and are easier to hit, because a
 * board has few columns and moving one renumbers every single one of them: with a {@code @Version}
 * on the entity, one stale write turns a drag into a partially applied order that neither person
 * asked for. What these pin is that the batch refuses anything it cannot apply as a whole.
 */
class ReorderLayoutTest {

    private static final TenancyFixtures.Tenant TENANT = TenancyFixtures.tenant();
    private static final Board BOARD = TENANT.board();
    private static final User CALLER = TENANT.caller();
    /** A different id and a different owner, so nothing on it is visible to CALLER. */
    private static final Board OTHER_BOARD = TenancyFixtures.board(99, TenancyFixtures.user(2));

    @Nested
    @DisplayName("stages")
    class Columns {

        private final Map<Integer, Column> stored = new HashMap<>();
        private final ColumnRepository columnRepository = mock(ColumnRepository.class);
        private final ColumnService service = new ColumnService(
                columnRepository, new ColumnMapper(new TaskMapper()),
                mock(TaskService.class), TENANT.boardService());

        private Column column(int id, Board board, int position) {
            var column = new Column();
            column.setId(id);
            column.setBoard(board);
            column.setPosition(position);
            column.setTasks(new ArrayList<>());
            stored.put(id, column);
            return column;
        }

        private void wireRepository() {
            when(columnRepository.findById(any()))
                    .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
            when(columnRepository.save(any(Column.class))).thenAnswer(call -> call.getArgument(0));
        }

        @Test
        @DisplayName("position becomes the index in the list")
        void positionsFollowTheList() {
            column(1, BOARD, 0);
            column(2, BOARD, 1);
            column(3, BOARD, 2);
            wireRepository();

            List<ColumnDto> reordered = service.reorderColumns(CALLER, List.of(3, 1, 2));

            assertThat(reordered).extracting(ColumnDto::id).containsExactly(3, 1, 2);
            assertThat(reordered).extracting(ColumnDto::position).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("a column on another board answers 404, and nothing is renumbered")
        void aColumnOnAnotherBoardIsNotFound() {
            var mine = column(1, BOARD, 0);
            column(2, OTHER_BOARD, 0);
            wireRepository();

            assertThatThrownBy(() -> service.reorderColumns(CALLER, List.of(1, 2)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.COLUMN_NOT_FOUND);

            assertThat(mine.getPosition()).isZero();
            verify(columnRepository, never()).save(any());
        }

        @Test
        @DisplayName("a repeated id is refused before anything is looked up")
        void aRepeatedIdIsRefused() {
            assertThatThrownBy(() -> service.reorderColumns(CALLER, List.of(1, 2, 1)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_REORDER);

            verify(columnRepository, never()).findById(any());
        }
    }

    @Nested
    @DisplayName("swimlanes")
    class Rows {

        private final Map<Integer, Row> stored = new HashMap<>();
        private final RowRepository rowRepository = mock(RowRepository.class);
        private final RowService service = new RowService(
                rowRepository, new RowMapper(new TaskMapper()),
                mock(TaskRepository.class), TENANT.boardService());

        private Row row(int id, Board board, int position) {
            var row = new Row();
            row.setId(id);
            row.setBoard(board);
            row.setPosition(position);
            row.setTasks(new ArrayList<>());
            stored.put(id, row);
            return row;
        }

        private void wireRepository() {
            when(rowRepository.findById(any()))
                    .thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
            when(rowRepository.save(any(Row.class))).thenAnswer(call -> call.getArgument(0));
        }

        @Test
        @DisplayName("position becomes the index in the list")
        void positionsFollowTheList() {
            row(1, BOARD, 0);
            row(2, BOARD, 1);
            wireRepository();

            List<RowDto> reordered = service.reorderRows(CALLER, List.of(2, 1));

            assertThat(reordered).extracting(RowDto::id).containsExactly(2, 1);
            assertThat(reordered).extracting(RowDto::position).containsExactly(0, 1);
        }

        @Test
        @DisplayName("a swimlane on another board answers 404")
        void aRowOnAnotherBoardIsNotFound() {
            row(1, BOARD, 0);
            row(2, OTHER_BOARD, 0);
            wireRepository();

            assertThatThrownBy(() -> service.reorderRows(CALLER, List.of(1, 2)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ROW_NOT_FOUND);

            verify(rowRepository, never()).save(any());
        }

        @Test
        @DisplayName("a repeated id is refused")
        void aRepeatedIdIsRefused() {
            assertThatThrownBy(() -> service.reorderRows(CALLER, List.of(4, 4)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_REORDER);

            verify(rowRepository, never()).findById(any());
        }

        @Test
        @DisplayName("one swimlane is a legitimate order and settles it at position 0")
        void oneIdIsAllowed() {
            row(1, BOARD, 5);
            wireRepository();

            assertThat(service.reorderRows(CALLER, List.of(1)))
                    .extracting(RowDto::position)
                    .containsExactly(0);
        }
    }
}
