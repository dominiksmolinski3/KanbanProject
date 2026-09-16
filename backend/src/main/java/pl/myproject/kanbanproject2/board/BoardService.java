package pl.myproject.kanbanproject2.board;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.invitation.BoardInvitationRepository;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.layout.row.RowRepository;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.activity.TaskActivityRepository;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Who may see which board, and everything that changes one. Every other service asks this one the
 * same two questions — {@link #resolve} for "which board is this about" and {@link #requireVisible}
 * for "may this caller touch it" — and the dependency runs one way only, feature services depending
 * on this and this on repositories, so a check can't be short-circuited by a service that has
 * already run one.
 *
 * <p><b>The leak profile is deliberate.</b> A board, column, row or task on someone else's board
 * answers exactly as one that does not exist — 404, never 403 — so a caller can't learn the shape of
 * a board they can't see by walking ids. 403 is reserved for a caller who can already see the object
 * and simply doesn't own it.
 */
@RequiredArgsConstructor
@Transactional
@Service
public class BoardService {

    /**
     * The stages a new board starts with, matching {@code V3__seed_default_columns.sql}. The seed
     * migration only ever fills an empty database once, so a board created afterward needs its
     * starting stages seeded here instead; V3 keeps its job for the one board that predates this code.
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
    private final BoardInvitationRepository invitationRepository;
    private final TaskActivityRepository activityRepository;
    private final BoardMapper boardMapper;

    // ------------------------------------------------------------------ access ---

    /**
     * The board a request without a target object is about. {@code boardId} may be null, meaning
     * "the caller's own board" — which is what lets the pre-boards screens keep working unchanged.
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
     * Fails unless both objects sit on the same board. Without this, a caller on two boards could
     * move a task onto a column on the other one, splitting a board across two tenancies.
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
     * The caller's own board, created if they have none. This is the only place a board is
     * provisioned, which is why it sits on the read path rather than in signup: accounts that
     * predate boards have none, and an owner who deletes their last one has none again — a GET that
     * writes is unusual, but the alternative is a screen with no way to render.
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
     * Gives a new account a board to work on. If the migration's unclaimed board is still sitting
     * there, the first account to open a board adopts it rather than getting a second, identical
     * set of stages beside the one {@code V3__seed_default_columns.sql} already seeded.
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
     * Removes the board and everything on it. The unwinding is spelled out here rather than
     * delegated to {@code ColumnService}/{@code TaskService}, since both depend on this service for
     * their access checks and calling back into them would create a dependency cycle. It still has
     * to honor their rules: {@code task_column_history.task_id} is not nullable and nothing cascades
     * to it, and a parent task can't be deleted while a child still points at it.
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
        // Nothing cascades to either of these, and a leftover invitation would render on the
        // invitee's screen as a board with no name.
        invitationRepository.deleteAll(invitationRepository.findByBoard(board));
        activityRepository.deleteAll(activityRepository.findByBoard(board));

        board.getMembers().clear();
        boardRepository.delete(board);
    }

    // ----------------------------------------------------------------- members ---

    /**
     * Puts an accepted invitee on the board — the only way onto a member list, since
     * {@code board/invitation} replaced an owner directly adding an address (which let an owner
     * diff the member list to learn whether that address had an account here). No access check of
     * its own: the caller is the person joining, and the check that matters — that the invitation is
     * pending and addressed to them — belongs to {@code board/invitation}, where it's made.
     */
    public Board addAcceptedMember(Board board, User user) {
        board.addMember(user);
        return boardRepository.save(board);
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
     * Takes the departing member off the board's tasks on the way out, or a stale assignment would
     * keep their name on a board they can no longer open and count against their WIP limit.
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
     * Keyed on id rather than collected into a {@code Set<User>}, because {@link User} does not
     * define equality and the same account reached through two boards is two distinct objects —
     * which is how the caller once came back listed twice.
     */
    public Collection<User> peersOf(User caller) {
        var peers = new LinkedHashMap<Integer, User>();
        for (Board board : visibleTo(caller)) {
            board.everyone().forEach(user -> peers.putIfAbsent(user.getId(), user));
        }
        return peers.values();
    }
}
