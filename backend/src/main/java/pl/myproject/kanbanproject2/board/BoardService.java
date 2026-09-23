package pl.myproject.kanbanproject2.board;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.invitation.BoardInvitationRepository;
import pl.myproject.kanbanproject2.chat.ChatRepository;
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

@RequiredArgsConstructor
@Transactional
@Service
public class BoardService {

    private static final List<DefaultColumn> DEFAULT_COLUMNS = List.of(
            new DefaultColumn("New Issues", 1, 0, null),
            new DefaultColumn("Icebox", 2, 0, null),
            new DefaultColumn("Product Backlog", 3, 0, null),
            new DefaultColumn("Sprint Backlog", 4, 10, null),
            new DefaultColumn("In Progress", 5, 5, FlowRole.START),
            new DefaultColumn("QA/Review", 6, 0, null),
            new DefaultColumn("Done", 7, 0, FlowRole.DONE),
            new DefaultColumn("Closed", 8, 0, null));

    private enum FlowRole { START, DONE }

    private record DefaultColumn(String name, int position, int wipLimit, FlowRole flowRole) {
    }

    static final String DEFAULT_BOARD_NAME = "Kanban";

    private final BoardRepository boardRepository;
    private final ColumnRepository columnRepository;
    private final RowRepository rowRepository;
    private final TaskRepository taskRepository;
    private final TaskColumnHistoryRepository taskColumnHistoryRepository;
    private final UserRepository userRepository;
    private final BoardInvitationRepository invitationRepository;
    private final TaskActivityRepository activityRepository;
    private final ChatRepository chatRepository;
    private final BoardMapper boardMapper;
    private final ApplicationEventPublisher events;

    public Board resolve(User caller, Integer boardId) {
        return boardId == null ? defaultFor(caller) : requireVisible(caller, boardId);
    }

    public Board requireVisible(User caller, Integer boardId) {
        return requireVisible(caller, boardRepository.findWithMembersById(boardId).orElse(null));
    }

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

    public BoardRole roleOf(User caller, Board board) {
        if (board == null || caller == null || caller.getId() == null) {
            return BoardRole.MEMBER;
        }
        if (board.isOwnedBy(caller)) {
            return BoardRole.MEMBER;
        }
        return boardRepository.findMemberRole(board.getId(), caller.getId())
                .map(BoardRole::valueOf)
                .orElse(BoardRole.MEMBER);
    }

    public boolean isWritable(User caller, Board board) {
        return board != null && board.isWritableBy(caller, roleOf(caller, board));
    }

    public Board requireWritable(User caller, Board board) {
        var visible = requireVisible(caller, board);
        if (!isWritable(caller, visible)) {
            throw new GlobalException(ExceptionIdentifier.VIEWER_READ_ONLY);
        }
        return visible;
    }

    public Board requireWritable(User caller, Integer boardId) {
        return requireWritable(caller, boardRepository.findWithMembersById(boardId).orElse(null));
    }

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
            var saved = columnRepository.save(column);
            if (seed.flowRole() == FlowRole.START) {
                board.setFlowStartColumn(saved);
            } else if (seed.flowRole() == FlowRole.DONE) {
                board.setFlowDoneColumn(saved);
            }
        }
    }

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
            events.publishEvent(new BoardTasksDeleting(board, tasks));
            taskRepository.deleteAll(tasks);
        }

        board.setFlowStartColumn(null);
        board.setFlowDoneColumn(null);
        columnRepository.deleteAll(columnRepository.findByBoardOrderByPositionAsc(board));
        rowRepository.deleteAll(rowRepository.findByBoardOrderByPositionAsc(board));
        invitationRepository.deleteAll(invitationRepository.findByBoard(board));
        activityRepository.deleteAll(activityRepository.findByBoard(board));
        chatRepository.deleteAll(chatRepository.findByBoard(board));

        board.getMembers().clear();
        boardRepository.delete(board);
    }

    public Board addAcceptedMember(Board board, User user) {
        return addAcceptedMember(board, user, BoardRole.MEMBER);
    }

    public Board addAcceptedMember(Board board, User user, BoardRole role) {
        board.addMember(user);
        var saved = boardRepository.saveAndFlush(board);
        boardRepository.updateMemberRole(saved.getId(), user.getId(), role.name());
        return saved;
    }

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

    public Collection<User> peersOf(User caller) {
        var peers = new LinkedHashMap<Integer, User>();
        for (Board board : visibleTo(caller)) {
            board.everyone().forEach(user -> peers.putIfAbsent(user.getId(), user));
        }
        return peers.values();
    }
}
