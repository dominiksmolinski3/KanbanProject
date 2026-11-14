package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.board.BoardDto;
import pl.myproject.kanbanproject2.board.BoardMapper;
import pl.myproject.kanbanproject2.board.BoardMemberDto;
import pl.myproject.kanbanproject2.board.BoardRepository;
import pl.myproject.kanbanproject2.board.BoardRole;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.CreateBoardRequest;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(DatabaseTestConfiguration.class)
@Import({BoardService.class, BoardMapper.class})
class BoardListDatabaseTest {

    @Autowired
    private BoardService boardService;

    @Autowired
    private BoardRepository boards;

    @Autowired
    private UserRepository users;

    @Autowired
    private TransactionTemplate transactions;

    private User account(String name) {
        return transactions.execute(status -> {
            var user = new User(name, name + "-" + UUID.randomUUID() + "@example.test", "hashed");
            user.setEnabled(true);
            return users.save(user);
        });
    }

    private Integer board(User owner, String name, User guest, BoardRole role) {
        return transactions.execute(status -> {
            var board = boards.findWithMembersById(boardService.createBoard(owner, new CreateBoardRequest(name)).id())
                    .orElseThrow();
            boardService.addAcceptedMember(board, guest, role);
            return board.getId();
        });
    }

    @Test
    @DisplayName("the board list gives each board its own roles, the same ones the single-board read gives")
    void theListCarriesEachBoardsRoles() {
        var owner = account("owner");
        var guest = account("guest");
        Integer readOnly = board(owner, "Read only", guest, BoardRole.VIEWER);
        Integer shared = board(owner, "Shared", guest, BoardRole.MEMBER);

        List<BoardDto> listed = transactions.execute(status -> boardService.myBoards(guest));

        assertThat(listed).extracting(BoardDto::id).containsExactly(readOnly, shared);
        assertThat(listed).extracting(BoardDto::role).containsExactly(BoardRole.VIEWER, BoardRole.MEMBER);
        assertThat(listed.get(0).members())
                .filteredOn(member -> member.id().equals(guest.getId()))
                .extracting(BoardMemberDto::role)
                .containsExactly(BoardRole.VIEWER);
        for (BoardDto board : listed) {
            BoardDto alone = transactions.execute(status -> boardService.getBoard(guest, board.id()));
            assertThat(board).isEqualTo(alone);
        }
    }

    @Test
    @DisplayName("an account on no board yet lists nothing")
    void nobodysBoardsIsAnEmptyList() {
        var newcomer = account("newcomer");

        List<BoardDto> listed = transactions.execute(status -> boardService.myBoards(newcomer));

        assertThat(listed).isEmpty();
    }
}
