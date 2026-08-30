package pl.myproject.kanbanproject2.board;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.file.File;
import pl.myproject.kanbanproject2.file.FileRepository;
import pl.myproject.kanbanproject2.file.FileService;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnMapper;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.column.ColumnService;
import pl.myproject.kanbanproject2.layout.row.Row;
import pl.myproject.kanbanproject2.layout.row.RowMapper;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.layout.row.RowService;
import pl.myproject.kanbanproject2.task.CreateTaskRequest;
import pl.myproject.kanbanproject2.task.IdRef;
import pl.myproject.kanbanproject2.task.PatchTaskRequest;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskMapper;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.TaskService;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryMapper;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserMapper;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-01 and SEC-05, stated as the property they are: <b>one account's board is invisible to
 * another's, on every route that reaches it.</b>
 *
 * <p>Two tenants, each with a board, a column, a swimlane, a task and an upload. Every assertion
 * here is a request that used to succeed. The services are real and only the repositories are
 * mocked, so the check under test is the one that ships — including {@link BoardService} itself,
 * which is constructed rather than stubbed.
 *
 * <p>The expected status is 404 throughout, never 403. That is deliberate and is the reason each
 * assertion names the identifier: a 403 on {@code /api/tasks/{id}} would let a caller walk the id
 * space and learn the size and shape of a board they cannot open.
 */
class TenantIsolationTest {

    private static final int MINE = 1;
    private static final int THEIRS = 2;

    private BoardRepository boardRepository;
    private TaskRepository taskRepository;
    private ColumnRepository columnRepository;
    private RowRepository rowRepository;
    private UserRepository userRepository;
    private FileRepository fileRepository;

    private BoardService boardService;
    private BoardMapper boardMapper;
    private TaskService taskService;
    private ColumnService columnService;
    private RowService rowService;
    private UserService userService;
    private FileService fileService;

    private User me;
    private User them;
    private Board myBoard;
    private Board theirBoard;

    @BeforeEach
    void setUp() {
        boardRepository = mock(BoardRepository.class);
        taskRepository = mock(TaskRepository.class);
        columnRepository = mock(ColumnRepository.class);
        rowRepository = mock(RowRepository.class);
        userRepository = mock(UserRepository.class);
        fileRepository = mock(FileRepository.class);
        var historyRepository = mock(TaskColumnHistoryRepository.class);

        me = TenancyFixtures.user(MINE);
        them = TenancyFixtures.user(THEIRS);
        myBoard = TenancyFixtures.board(10, me);
        theirBoard = TenancyFixtures.board(20, them);

        when(boardRepository.findWithMembersById(10)).thenReturn(Optional.of(myBoard));
        when(boardRepository.findWithMembersById(20)).thenReturn(Optional.of(theirBoard));
        when(boardRepository.findByOwnerOrderByIdAsc(me)).thenReturn(List.of(myBoard));
        when(boardRepository.findByOwnerOrderByIdAsc(them)).thenReturn(List.of(theirBoard));
        when(boardRepository.findVisibleTo(me)).thenReturn(List.of(myBoard));
        when(boardRepository.findVisibleTo(them)).thenReturn(List.of(theirBoard));
        when(historyRepository.findByTaskOrderByChangedAtDesc(any())).thenReturn(List.of());
        when(taskRepository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
        when(taskRepository.findMaxPosition(any(), any(), any())).thenReturn(Optional.empty());

        boardMapper = new BoardMapper(new UserMapper());
        boardService = new BoardService(boardRepository, columnRepository, rowRepository,
                taskRepository, historyRepository, userRepository, boardMapper);

        var taskMapper = new TaskMapper();
        userService = new UserService(userRepository, new UserMapper(), taskRepository, boardService);
        taskService = new TaskService(taskRepository, userRepository, taskMapper, userService,
                historyRepository, mock(TaskColumnHistoryMapper.class), columnRepository,
                rowRepository, boardService);
        columnService = new ColumnService(columnRepository, new ColumnMapper(taskMapper),
                taskService, boardService);
        rowService = new RowService(rowRepository, new RowMapper(taskMapper), taskRepository, boardService);
        fileService = new FileService(fileRepository);
    }

    private Task theirTask(int id) {
        var task = new Task();
        task.setId(id);
        task.setTitle("theirs");
        task.setPosition(1);
        task.setLabels(Set.of());
        task.setBoard(theirBoard);
        when(taskRepository.findById(id)).thenReturn(Optional.of(task));
        return task;
    }

    private Column theirColumn(int id) {
        var column = new Column();
        column.setId(id);
        column.setName("theirs");
        column.setBoard(theirBoard);
        when(columnRepository.findById(id)).thenReturn(Optional.of(column));
        return column;
    }

    private Row theirRow(int id) {
        var row = new Row();
        row.setId(id);
        row.setName("theirs");
        row.setBoard(theirBoard);
        when(rowRepository.findById(id)).thenReturn(Optional.of(row));
        return row;
    }

    private static void expect(ExceptionIdentifier identifier, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(identifier);
    }

    @Nested
    @DisplayName("tasks")
    class Tasks {

        @Test
        @DisplayName("reading, patching, deleting and moving somebody else's task all answer 404")
        void everyTaskRouteIsClosed() {
            theirTask(7);

            expect(ExceptionIdentifier.TASK_NOT_FOUND, () -> taskService.getTaskById(me, 7));
            expect(ExceptionIdentifier.TASK_NOT_FOUND, () -> taskService.deleteTask(me, 7));
            expect(ExceptionIdentifier.TASK_NOT_FOUND, () -> taskService.updateTaskPosition(me, 7, 3));
            expect(ExceptionIdentifier.TASK_NOT_FOUND, () -> taskService.updateTaskCompletion(me, 7, true));
            expect(ExceptionIdentifier.TASK_NOT_FOUND, () -> taskService.setDailyFocus(me, 7, true));
            expect(ExceptionIdentifier.TASK_NOT_FOUND, () -> taskService.addLabelToTask(me, 7, "mine"));
            expect(ExceptionIdentifier.TASK_NOT_FOUND, () -> taskService.updateTaskLabels(me, 7, Set.of("x")));
            expect(ExceptionIdentifier.TASK_NOT_FOUND, () -> taskService.assignUserToTask(me, 7, MINE));
            expect(ExceptionIdentifier.TASK_NOT_FOUND, () -> taskService.getChildTasks(me, 7));
            expect(ExceptionIdentifier.TASK_NOT_FOUND, () -> taskService.getTaskColumnHistoryDTOs(me, 7));

            verify(taskRepository, never()).delete(any());
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("the listing answers with my board only, whoever else has tasks")
        void listingIsScoped() {
            taskService.getAllTasks(me, null);

            verify(taskRepository).findByBoardOrderByIdAsc(myBoard);
            verify(taskRepository, never()).findByBoardOrderByIdAsc(theirBoard);
        }

        @Test
        @DisplayName("asking for their board by id is a 404, not a listing")
        void cannotAskForAnotherBoardById() {
            expect(ExceptionIdentifier.BOARD_NOT_FOUND, () -> taskService.getAllTasks(me, 20));
        }

        @Test
        @DisplayName("a task cannot be created into somebody else's column")
        void cannotCreateIntoTheirColumn() {
            theirColumn(4);

            expect(ExceptionIdentifier.COLUMN_NOT_FOUND, () -> taskService.addTask(me, null,
                    new CreateTaskRequest("mine", null, null, null, null, new IdRef(4), null)));
        }

        @Test
        @DisplayName("a task cannot be moved into somebody else's swimlane")
        void cannotMoveIntoTheirRow() {
            var mine = new Task();
            mine.setId(3);
            mine.setBoard(myBoard);
            mine.setLabels(Set.of());
            when(taskRepository.findById(3)).thenReturn(Optional.of(mine));
            theirRow(9);

            expect(ExceptionIdentifier.ROW_NOT_FOUND, () -> taskService.patchTask(me, 3, patchRow(9)));
        }

        @Test
        @DisplayName("a dependency cannot be made to point at a task on another board")
        void cannotParentAcrossBoards() {
            var mine = new Task();
            mine.setId(3);
            mine.setBoard(myBoard);
            mine.setLabels(Set.of());
            when(taskRepository.findById(3)).thenReturn(Optional.of(mine));
            theirTask(7);

            // Otherwise one board's progress would wait on work its members cannot see, and the
            // un-completion cascade would reach into a board the caller was never on.
            expect(ExceptionIdentifier.PARENT_TASK_NOT_FOUND,
                    () -> taskService.assignParentTask(me, 3, 7));
        }

        @Test
        @DisplayName("somebody who is not on the board cannot be assigned to its work")
        void cannotAssignANonMember() {
            var mine = new Task();
            mine.setId(3);
            mine.setBoard(myBoard);
            mine.setLabels(Set.of());
            when(taskRepository.findById(3)).thenReturn(Optional.of(mine));
            when(userRepository.findById(THEIRS)).thenReturn(Optional.of(them));

            expect(ExceptionIdentifier.USER_NOT_FOUND, () -> taskService.assignUserToTask(me, 3, THEIRS));
        }

        private static PatchTaskRequest patchRow(Integer rowId) {
            return new PatchTaskRequest(JsonNullable.undefined(), JsonNullable.undefined(),
                    JsonNullable.undefined(), JsonNullable.undefined(), JsonNullable.undefined(),
                    JsonNullable.undefined(), JsonNullable.of(new IdRef(rowId)));
        }
    }

    @Nested
    @DisplayName("the layout")
    class Layout {

        @Test
        @DisplayName("somebody else's column cannot be read, renamed, moved or deleted")
        void columnsAreClosed() {
            theirColumn(4);

            expect(ExceptionIdentifier.COLUMN_NOT_FOUND, () -> columnService.getColumnById(me, 4));
            expect(ExceptionIdentifier.COLUMN_NOT_FOUND, () -> columnService.deleteColumn(me, 4));
            expect(ExceptionIdentifier.COLUMN_NOT_FOUND, () -> columnService.updateColumnPosition(me, 4, 1));
            expect(ExceptionIdentifier.COLUMN_NOT_FOUND, () ->
                    columnService.patchColumn(me, new ColumnDtoStub().dto(), 4));

            verify(columnRepository, never()).delete(any());
        }

        @Test
        @DisplayName("somebody else's swimlane cannot be read, renamed, moved or deleted")
        void rowsAreClosed() {
            theirRow(9);

            expect(ExceptionIdentifier.ROW_NOT_FOUND, () -> rowService.getRowById(me, 9));
            expect(ExceptionIdentifier.ROW_NOT_FOUND, () -> rowService.deleteRow(me, 9));
            expect(ExceptionIdentifier.ROW_NOT_FOUND, () -> rowService.updateRowPosition(me, 9, 1));

            verify(rowRepository, never()).delete(any());
        }

        @Test
        @DisplayName("both listings are scoped to the caller's own board")
        void listingsAreScoped() {
            columnService.getAllColumns(me, null);
            rowService.getAllRows(me, null);

            verify(columnRepository).findByBoardOrderByPositionAsc(myBoard);
            verify(rowRepository).findByBoardOrderByPositionAsc(myBoard);
        }

        /** ColumnDto carries a task list; only the patched fields matter here. */
        private static final class ColumnDtoStub {
            pl.myproject.kanbanproject2.layout.column.ColumnDto dto() {
                return new pl.myproject.kanbanproject2.layout.column.ColumnDto(
                        null, "renamed", null, null, List.of());
            }
        }
    }

    @Nested
    @DisplayName("accounts")
    class Accounts {

        @Test
        @DisplayName("the user listing shows the people I share a board with, not the whole table")
        void listingIsScopedToPeers() {
            var listed = userService.getVisibleUsers(me);

            assertThat(listed).extracting(u -> u.id()).containsExactly(MINE);
            // It used to answer userRepository.findAll(): every address and display name on the
            // deployment, to anyone who could log in.
            verify(userRepository, never()).findAll();
        }

        @Test
        @DisplayName("an account I share no board with is a 404, by id and by avatar")
        void strangersAreNotReadable() {
            when(userRepository.findById(THEIRS)).thenReturn(Optional.of(them));

            expect(ExceptionIdentifier.USER_NOT_FOUND, () -> userService.getUserById(me, THEIRS));
            expect(ExceptionIdentifier.USER_NOT_FOUND, () -> userService.requireVisibleUser(me, THEIRS));
            expect(ExceptionIdentifier.USER_NOT_FOUND, () -> userService.getWipStatus(me, THEIRS));
        }

        @Test
        @DisplayName("the caller is listed once, not once per instance of themselves")
        void theCallerIsNotDuplicated() {
            /*
             * Found by running it, not by reading it. The caller arrives from the JWT filter and
             * the board's members from the persistence context, so they are two objects for one
             * account - and User inherits identity equality, so a Set kept both. The board page
             * showed whoever was looking at it twice.
             */
            myBoard.addMember(me);

            assertThat(userService.getVisibleUsers(me)).hasSize(1);
            assertThat(boardMapper.apply(myBoard, me).members()).extracting(u -> u.id())
                    .containsExactly(MINE);
        }

        @Test
        @DisplayName("somebody on two of my boards is still listed once")
        void peersAreNotDuplicatedAcrossBoards() {
            var second = TenancyFixtures.board(11, me);
            second.addMember(them);
            myBoard.addMember(them);
            when(boardRepository.findVisibleTo(me)).thenReturn(List.of(myBoard, second));

            assertThat(userService.getVisibleUsers(me)).extracting(u -> u.id())
                    .containsExactly(MINE, THEIRS);
        }

        @Test
        @DisplayName("a board member is readable, because the board has to render their name")
        void peersAreReadable() {
            myBoard.addMember(them);
            when(userRepository.findById(THEIRS)).thenReturn(Optional.of(them));

            assertThat(userService.getUserById(me, THEIRS).id()).isEqualTo(THEIRS);
            assertThat(userService.getVisibleUsers(me)).extracting(u -> u.id())
                    .containsExactly(MINE, THEIRS);
        }

        @Test
        @DisplayName("a WIP limit belongs to its account - nobody else can set it")
        void wipLimitIsSelfOnly() {
            myBoard.addMember(them);
            when(userRepository.findById(THEIRS)).thenReturn(Optional.of(them));

            expect(ExceptionIdentifier.NOT_ACCOUNT_OWNER,
                    () -> userService.updateWipLimit(me, THEIRS, 5));
        }
    }

    @Nested
    @DisplayName("files")
    class Files {

        @Test
        @DisplayName("an upload belongs to whoever uploaded it, and nobody else can read or delete it")
        void uploadsAreOwned() {
            var theirs = new File("secret.pdf", "application/pdf", new byte[]{1}, them);
            theirs.setId(9L);
            when(fileRepository.findById(9L)).thenReturn(Optional.of(theirs));

            // Sequential ids: before there was an owner, GET /api/files/1..n read every upload in
            // the deployment and DELETE destroyed them.
            expect(ExceptionIdentifier.FILE_NOT_FOUND, () -> fileService.getFile(me, 9L));
            expect(ExceptionIdentifier.FILE_NOT_FOUND, () -> fileService.deleteFile(me, 9L));
            verify(fileRepository, never()).delete(any());

            assertThat(fileService.getFile(them, 9L)).isSameAs(theirs);
        }

        @Test
        @DisplayName("a file uploaded before there was an owner column belongs to nobody")
        void unownedFilesAreUnreachable() {
            var legacy = new File("old.bin", "application/octet-stream", new byte[]{1});
            legacy.setId(3L);
            when(fileRepository.findById(3L)).thenReturn(Optional.of(legacy));

            // Guessing an owner would be worse than admitting there isn't one. V5 recovers the
            // owner of an avatar from users.avatar_id and has nothing to go on for anything else.
            expect(ExceptionIdentifier.FILE_NOT_FOUND, () -> fileService.getFile(me, 3L));
            expect(ExceptionIdentifier.FILE_NOT_FOUND, () -> fileService.getFile(them, 3L));
        }
    }
}
