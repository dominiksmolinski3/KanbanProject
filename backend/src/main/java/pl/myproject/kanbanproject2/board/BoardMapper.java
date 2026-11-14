package pl.myproject.kanbanproject2.board;

import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.user.User;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BoardMapper {

    private final BoardRepository boardRepository;

    public BoardMapper(BoardRepository boardRepository) {
        this.boardRepository = boardRepository;
    }

    public BoardDto apply(Board board, User caller) {
        if (board == null) {
            return null;
        }

        Map<Integer, String> roleByUserId = new HashMap<>();
        for (Object[] row : boardRepository.findMemberRoles(board.getId())) {
            roleByUserId.put(((Number) row[0]).intValue(), (String) row[1]);
        }
        return describe(board, caller, roleByUserId);
    }

    public List<BoardDto> applyAll(List<Board> boards, User caller) {
        if (boards.isEmpty()) {
            return List.of();
        }
        Map<Integer, Map<Integer, String>> rolesByBoardId = new HashMap<>();
        for (Object[] row : boardRepository.findMemberRolesIn(boards.stream().map(Board::getId).toList())) {
            rolesByBoardId.computeIfAbsent(((Number) row[0]).intValue(), boardId -> new HashMap<>())
                    .put(((Number) row[1]).intValue(), (String) row[2]);
        }
        return boards.stream()
                .map(board -> describe(board, caller, rolesByBoardId.getOrDefault(board.getId(), Map.of())))
                .toList();
    }

    private static BoardDto describe(Board board, User caller, Map<Integer, String> roleByUserId) {
        List<BoardMemberDto> members = board.everyone().stream()
                .sorted(Comparator.comparing(User::getId))
                .map(user -> new BoardMemberDto(
                        user.getId(),
                        user.getEmail(),
                        user.getName(),
                        user.getWipLimit(),
                        user.getLocale(),
                        user.getAvatarBlobName() != null,
                        roleOf(roleByUserId, user.getId())))
                .toList();

        BoardRole callerRole = board.isOwnedBy(caller)
                ? BoardRole.MEMBER
                : roleOf(roleByUserId, caller == null ? null : caller.getId());

        return new BoardDto(
                board.getId(),
                board.getName(),
                board.getOwner() == null ? null : board.getOwner().getId(),
                board.isOwnedBy(caller),
                members,
                callerRole);
    }

    private static BoardRole roleOf(Map<Integer, String> roleByUserId, Integer userId) {
        String stored = userId == null ? null : roleByUserId.get(userId);
        return stored == null ? BoardRole.MEMBER : BoardRole.valueOf(stored);
    }
}
