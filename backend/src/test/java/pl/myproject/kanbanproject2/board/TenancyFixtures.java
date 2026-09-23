package pl.myproject.kanbanproject2.board;

import pl.myproject.kanbanproject2.user.User;

import java.util.LinkedHashSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class TenancyFixtures {
    public static final int BOARD_ID = 77;

    private TenancyFixtures() {
    }

    public static User user(int id) {
        var user = new User("User " + id, "user" + id + "@example.com", "hashed");
        user.setId(id);
        return user;
    }

    public static Board board(int id, User owner) {
        var board = new Board("Board " + id, owner);
        board.setId(id);
        board.setMembers(new LinkedHashSet<>());
        if (owner != null) {
            board.getMembers().add(owner);
        }
        return board;
    }

    public record Tenant(User caller, Board board, BoardService boardService) {
    }

    public static Tenant tenant() {
        var caller = user(1);
        var board = board(BOARD_ID, caller);
        return new Tenant(caller, board, boardServiceReturning(board));
    }

    public static BoardService boardServiceReturning(Board board) {
        var boardService = mock(BoardService.class);
        when(boardService.resolve(any(), any())).thenReturn(board);
        when(boardService.defaultFor(any())).thenReturn(board);
        when(boardService.requireVisible(any(User.class), any(Board.class))).thenReturn(board);
        when(boardService.requireWritable(any(User.class), any(Board.class))).thenReturn(board);
        doCallRealMethod().when(boardService).requireSameBoard(any(), any());
        return boardService;
    }
}
