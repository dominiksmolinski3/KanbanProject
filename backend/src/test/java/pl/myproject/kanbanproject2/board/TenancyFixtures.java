package pl.myproject.kanbanproject2.board;

import pl.myproject.kanbanproject2.user.User;

import java.util.LinkedHashSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One board, its owner, and a {@link BoardService} that hands that board to whatever asks — every
 * unit test written before boards existed now needs a caller and a board, named once here.
 *
 * <p>{@code requireSameBoard} deliberately calls through to the real implementation, since it is an
 * invariant rather than a lookup and a mock that silently passes would defang the tests that move a
 * task between cells.
 */
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

    /** The owner, their board, and a service that resolves every request to it. */
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
        // Write-allowed by default, since every fixture caller before FEAT-08 owned the board it
        // hands back - a test exercising a viewer stubs requireWritable itself to throw instead.
        when(boardService.requireWritable(any(User.class), any(Board.class))).thenReturn(board);
        doCallRealMethod().when(boardService).requireSameBoard(any(), any());
        return boardService;
    }
}
