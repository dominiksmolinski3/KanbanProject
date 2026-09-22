package pl.myproject.kanbanproject2.layout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
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
import pl.myproject.kanbanproject2.task.TaskMapper;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.TaskService;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FEAT-08 on the layout side, the {@code ColumnService}/{@code RowService} half of
 * {@code TaskServiceWritabilityTest}: every mutation asks {@code BoardService.requireWritable},
 * which a viewer fails with {@code VIEWER_READ_ONLY} (403) even though the object is visible to
 * them. A fresh {@link TenancyFixtures.Tenant} per test, unlike {@code LayoutServiceTest}'s shared
 * static one, since a re-stubbed {@code requireWritable} would otherwise leak into other tests.
 */
class LayoutServiceWritabilityTest {

    private TenancyFixtures.Tenant tenant;
    private User caller;
    private Board board;
    private BoardService boardService;

    @BeforeEach
    void setUp() {
        tenant = TenancyFixtures.tenant();
        caller = tenant.caller();
        board = tenant.board();
        boardService = tenant.boardService();

        doThrow(new GlobalException(ExceptionIdentifier.VIEWER_READ_ONLY))
                .when(boardService).requireWritable(any(User.class), any(Board.class));
    }

    private static void expectReadOnly(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.VIEWER_READ_ONLY);
    }

    @Nested
    @DisplayName("stages")
    class Columns {

        private final ColumnRepository repository = mock(ColumnRepository.class);
        private ColumnService service;

        @BeforeEach
        void setUp() {
            service = new ColumnService(repository, new ColumnMapper(new TaskMapper()),
                    mock(TaskService.class), boardService, mock(BoardEventPublisher.class),
                    mock(TaskColumnHistoryRepository.class));
        }

        private Column column(int id) {
            var column = new Column();
            column.setId(id);
            column.setBoard(board);
            column.setName("To Do");
            when(repository.findById(id)).thenReturn(Optional.of(column));
            return column;
        }

        @Test
        @DisplayName("a viewer cannot create a column")
        void cannotCreate() {
            expectReadOnly(() -> service.addNewColumn(caller, null, new CreateColumnRequest("Review", null, null)));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a viewer cannot rename a column they can see")
        void cannotPatch() {
            column(1);
            expectReadOnly(() -> service.patchColumn(caller, new ColumnDto(null, "Doing", null, null, null), 1));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a viewer cannot delete a column")
        void cannotDelete() {
            column(1);
            expectReadOnly(() -> service.deleteColumn(caller, 1));
            verify(repository, never()).delete(any());
        }

        @Test
        @DisplayName("a viewer cannot reorder the stages")
        void cannotReorder() {
            column(1);
            column(2);
            expectReadOnly(() -> service.reorderColumns(caller, List.of(1, 2)));
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("swimlanes")
    class Rows {

        private final RowRepository repository = mock(RowRepository.class);
        private RowService service;

        @BeforeEach
        void setUp() {
            service = new RowService(repository, new RowMapper(new TaskMapper()),
                    mock(TaskRepository.class), boardService, mock(BoardEventPublisher.class));
        }

        private Row row(int id) {
            var row = new Row();
            row.setId(id);
            row.setBoard(board);
            row.setName("Features");
            when(repository.findById(id)).thenReturn(Optional.of(row));
            return row;
        }

        @Test
        @DisplayName("a viewer cannot create a swimlane")
        void cannotCreate() {
            expectReadOnly(() -> service.createRow(caller, null, new CreateRowRequest("Bugs", null, null)));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a viewer cannot rename a swimlane they can see")
        void cannotPatch() {
            row(1);
            expectReadOnly(() -> service.patchRow(caller, new RowDto(null, null, 2, null, null), 1));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a viewer cannot delete a swimlane")
        void cannotDelete() {
            row(1);
            expectReadOnly(() -> service.deleteRow(caller, 1));
            verify(repository, never()).delete(any());
        }

        @Test
        @DisplayName("a viewer cannot reorder the swimlanes")
        void cannotReorder() {
            row(1);
            row(2);
            expectReadOnly(() -> service.reorderRows(caller, List.of(1, 2)));
            verify(repository, never()).save(any());
        }
    }
}
