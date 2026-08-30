package pl.myproject.kanbanproject2.board;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Who may see which board, and everything that changes one.
 *
 * <p>Every other service asks this one the same two questions — {@link #resolve} for "which board
 * is this request about" and {@link #requireVisible} for "may this caller touch it" — which is why
 * the checks live here and not repeated in six services. The dependency runs one way only: the
 * feature services depend on this, and this depends on repositories, so nothing here can be
 * tempted into calling back through a service that has already checked access.
 *
 * <p><b>The leak profile is deliberate.</b> A board, column, row or task that exists on someone
 * else's board answers exactly as one that does not exist at all — 404, never 403. A caller must
 * not be able to learn the shape of a board they cannot see by walking ids. 403 is reserved for
 * the case where the caller can already see the object and simply is not its owner, which tells
 * them nothing they did not already know.
 */
@RequiredArgsConstructor
@Transactional
@Service
public class BoardService {

    /**
     * The stages a new board starts with, matching {@code V3__seed_default_columns.sql}.
     *
     * <p>The seed migration could only ever fill an empty database once, and a board is now created
     * every time an account needs one, so the starting stages have to come from somewhere that runs
     * then — here. V3 keeps its job for the one board that predates this code.
     */
    private static final List<DefaultColumn> DEFAULT_COLUMNS = List.of(
            new DefaultColumn("New Issues", 1, 0),
            new DefaultColumn("Icebox", 2, 0),
            new DefaultColumn("Product Backlog", 3, 0),
            new DefaultColumn("Sprint Backlog", 4, 10),
            new DefaultColumn("In Progress", 5, 5),
            new DefaultColumn("QA/Review", 6, 0),
            new DefaultColumn("Done", 7, 0),
            new DefaultColumn("Closed", 8, 0));

    private record DefaultColumn(String name, int position, int wipLimit) {
    }

    /** Language-neutral on purpose: the UI is translated into nine locales, this string is not. */
    static final String DEFAULT_BOARD_NAME = "Kanban";

    private final BoardRepository boardRepository;
    private final ColumnRepository columnRepository;
    private final RowRepository rowRepository;
    private final TaskRepository taskRepository;
    private final TaskColumnHistoryRepository taskColumnHistoryRepository;
    private final UserRepository userRepository;
    private final BoardMapper boardMapper;

    // ------------------------------------------------------------------ access ---

    /**
     * The board a request without a target object is about.
     *
     * <p>{@code boardId} is what the client asked for and may be null, which means "the caller's
     * own board" — the listings and the creates send nothing until the client has more than one
     * board to choose between, and that is the whole of the compatibility story for the existing
     * screens.
     */
    public Board resolve(User caller, Integer boardId) {
        return boardId == null ? defaultFor(caller) : requireVisible(caller, boardId);
    }

    public Board requireVisible(User caller, Integer boardId) {
        return requireVisible(caller, boardRepository.findWithMembersById(boardId).orElse(null));
    }

    /** @throws GlobalException 404 if the board is absent or belongs to somebody else. */
    public Board requireVisible(User caller, Board board) {
        if (board == null || !board.isVisibleTo(caller)) {
            throw new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND);
        }
        return board;
    }

    public Board requireOwned(User caller, Integer boardId) {
        var board = requireVisible(caller, boardId);
        if (!board.isOwnedBy(caller)) {
            throw new GlobalException(ExceptionIdentifier.NOT_BOARD_OWNER);
        }
        return board;
    }

    /**
     * Fails unless both objects sit on the same board.
     *
     * <p>Membership alone is not enough once a caller belongs to two boards: without this, moving a
     * task to a column on the other one would quietly split a board across two tenancies, and the
     * task would then be reachable from a board that does not contain its column.
     */
    public void requireSameBoard(Board expected, Board actual) {
        if (expected == null || actual == null || !expected.getId().equals(actual.getId())) {
            throw new GlobalException(ExceptionIdentifier.BOARD_MISMATCH);
        }
    }

    public List<Board> visibleTo(User caller) {
        if (caller == null || caller.getId() == null) {
            return List.of();
        }
        return boardRepository.findVisibleTo(caller);
    }

    /**
     * The caller's own board, created if they have none.
     *
     * <p>This is the only place a board is provisioned, which is why it sits on the read path
     * rather than in signup. Accounts that predate this code have none; an owner who deletes their
     * last one has none again; and an account that never verifies should not leave one behind. A
     * GET that writes is unusual, and the alternative is a screen that cannot render with no way
     * out of it from the UI.
     */
    public Board defaultFor(User caller) {
        if (caller == null || caller.getId() == null) {
            throw new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND);
        }
        var owned = boardRepository.findByOwnerOrderByIdAsc(caller);
        if (!owned.isEmpty()) {
            return owned.getFirst();
        }
        var visible = visibleTo(caller);
        if (!visible.isEmpty()) {
            return visible.getFirst();
        }
        return provisionFor(caller);
    }

    // ------------------------------------------------------------ provisioning ---

    /**
     * Gives a new account a board to work on.
     *
     * <p>If the migration's unclaimed board is still sitting there, the first account to open a
     * board adopts it rather than starting an empty one beside it. That is what makes a fresh
     * deployment coherent: {@code V3__seed_default_columns.sql} puts eight stages in the database
     * before any account exists, and without adoption those stages would belong to a board nobody
     * could ever see while the first user got a second, identical set.
     */
    public Board provisionFor(User user) {
        var unclaimed = boardRepository.findFirstByOwnerIsNullOrderByIdAsc();
        if (unclaimed.isPresent()) {
            var board = unclaimed.get();
            board.setOwner(user);
            board.addMember(user);
            return boardRepository.save(board);
        }

        var board = boardRepository.save(new Board(DEFAULT_BOARD_NAME, user));
        seedDefaultColumns(board);
        return board;
    }

    private void seedDefaultColumns(Board board) {
        for (DefaultColumn seed : DEFAULT_COLUMNS) {
            var column = new Column();
            column.setName(seed.name());
            column.setPosition(seed.position());
            column.setWipLimit(seed.wipLimit());
            column.setBoard(board);
            columnRepository.save(column);
        }
    }

    // -------------------------------------------------------------------- CRUD ---

    public List<BoardDto> myBoards(User caller) {
        return visibleTo(caller).stream().map(board -> boardMapper.apply(board, caller)).toList();
    }

    public BoardDto getBoard(User caller, Integer id) {
        return boardMapper.apply(requireVisible(caller, id), caller);
    }

    public BoardDto currentBoard(User caller) {
        return boardMapper.apply(defaultFor(caller), caller);
    }

    public BoardDto createBoard(User caller, CreateBoardRequest request) {
        var board = boardRepository.save(new Board(request.name(), caller));
        seedDefaultColumns(board);
        return boardMapper.apply(board, caller);
    }

    public BoardDto renameBoard(User caller, Integer id, PatchBoardRequest request) {
        var board = requireOwned(caller, id);
        board.setName(request.name());
        return boardMapper.apply(boardRepository.save(board), caller);
    }

    /**
     * Removes the board and everything on it.
     *
     * <p>The unwinding is spelled out here rather than delegated to {@code ColumnService} and
     * {@code TaskService}, which already know how to take one of each apart: those services depend
     * on this one for their access checks, and calling back into them would close the loop into a
     * dependency cycle Spring can only untangle with {@code @Lazy}. The two rules that matter are
     * the same ones they encode — {@code task_column_history.task_id} is not nullable and nothing
     * cascades to it, and a parent task cannot be deleted while a child still points at it.
     */
    public void deleteBoard(User caller, Integer id) {
        var board = requireOwned(caller, id);

        var tasks = taskRepository.findByBoardOrderByIdAsc(board);
        if (!tasks.isEmpty()) {
            for (Task task : tasks) {
                task.setParentTask(null);
                task.getChildTasks().clear();
            }
            taskRepository.saveAll(tasks);
            taskColumnHistoryRepository.deleteAll(taskColumnHistoryRepository.findByTaskIn(tasks));
            taskRepository.deleteAll(tasks);
        }

        columnRepository.deleteAll(columnRepository.findByBoardOrderByPositionAsc(board));
        rowRepository.deleteAll(rowRepository.findByBoardOrderByPositionAsc(board));

        board.getMembers().clear();
        boardRepository.delete(board);
    }

    // ----------------------------------------------------------------- members ---

    /**
     * Adds the account that signed up with {@code email}, if there is one.
     *
     * <p><b>An unknown address is not an error.</b> The response is the board either way, so the
     * owner is told what the board now looks like rather than whether that address has an account
     * here. This is weaker than the uniform answers on the unauthenticated routes — the member list
     * comes back in the same response, so an owner who compares it before and after can still tell.
     * Closing that properly means invitations the invitee accepts, which is a feature rather than a
     * check; what this avoids is the blunter version where the API says "no such user" outright.
     */
    public BoardDto addMember(User caller, Integer id, AddMemberRequest request) {
        var board = requireOwned(caller, id);
        userRepository.findByEmail(request.email()).ifPresent(board::addMember);
        return boardMapper.apply(boardRepository.save(board), caller);
    }

    /**
     * Takes somebody off the board — the owner removing a member, or a member removing themselves.
     *
     * <p>The owner cannot be removed, including by themselves: the board would be left with nobody
     * able to rename, share or delete it, and no route anywhere could put an owner back.
     */
    public BoardDto removeMember(User caller, Integer id, Integer userId) {
        var board = requireVisible(caller, id);
        boolean leaving = caller.getId().equals(userId);
        if (!leaving && !board.isOwnedBy(caller)) {
            throw new GlobalException(ExceptionIdentifier.NOT_BOARD_OWNER);
        }
        if (board.getOwner() != null && board.getOwner().getId().equals(userId)) {
            throw new GlobalException(ExceptionIdentifier.CANNOT_REMOVE_BOARD_OWNER);
        }

        var member = userRepository.findById(userId)
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.USER_NOT_FOUND,
                        "User not found with id: " + userId));
        unassignFromBoardTasks(board, member);
        board.removeMember(member);
        return boardMapper.apply(boardRepository.save(board), caller);
    }

    /**
     * Takes the departing member off the board's tasks on the way out. Leaving a stale assignment
     * behind would keep their name on a board they can no longer open, and would keep counting
     * against their WIP limit for work they cannot reach.
     */
    private void unassignFromBoardTasks(Board board, User member) {
        var assigned = taskRepository.findByBoardOrderByIdAsc(board).stream()
                .filter(task -> task.getUsers() != null && task.getUsers().stream()
                        .anyMatch(user -> user.getId().equals(member.getId())))
                .toList();
        for (Task task : assigned) {
            task.getUsers().removeIf(user -> user.getId().equals(member.getId()));
        }
        taskRepository.saveAll(assigned);
    }

    /**
     * Every account that shares at least one board with {@code caller}, one entry per account.
     *
     * <p>Collected into a map keyed on id, because the same account reached through two boards is
     * two objects and {@link User} does not define equality. Running this for real is what caught
     * that: the caller came back listed twice, once as themselves and once as a board member.
     */
    public Collection<User> peersOf(User caller) {
        var peers = new LinkedHashMap<Integer, User>();
        for (Board board : visibleTo(caller)) {
            board.everyone().forEach(user -> peers.putIfAbsent(user.getId(), user));
        }
        return peers.values();
    }
}
