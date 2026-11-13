package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardMapper;
import pl.myproject.kanbanproject2.board.BoardRepository;
import pl.myproject.kanbanproject2.board.BoardRole;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.CreateBoardRequest;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserMapper;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(DatabaseTestConfiguration.class)
@Import({BoardService.class, BoardMapper.class, UserService.class, UserMapper.class})
class WipStatusDatabaseTest {

    @Autowired
    private UserService userService;

    @Autowired
    private BoardService boardService;

    @Autowired
    private UserRepository users;

    @Autowired
    private BoardRepository boards;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TransactionTemplate transactions;

    private User account(String name, Integer wipLimit) {
        return transactions.execute(status -> {
            var user = new User(name, name + "-" + UUID.randomUUID() + "@example.test", "hashed");
            user.setEnabled(true);
            user.setWipLimit(wipLimit);
            return users.save(user);
        });
    }

    private Integer boardOf(User owner, User... members) {
        return transactions.execute(status -> {
            var board = boards.findWithMembersById(boardService.createBoard(owner, new CreateBoardRequest("Work")).id())
                    .orElseThrow();
            for (User member : members) {
                boardService.addAcceptedMember(board, member, BoardRole.MEMBER);
            }
            return board.getId();
        });
    }

    private void assign(Integer boardId, User assignee, int open, int completed) {
        transactions.executeWithoutResult(status -> {
            Board board = boards.findWithMembersById(boardId).orElseThrow();
            for (int i = 0; i < open + completed; i++) {
                var task = new Task();
                task.setTitle("card " + i);
                task.setBoard(board);
                task.setCompleted(i >= open);
                task.getUsers().add(users.findById(assignee.getId()).orElseThrow());
                tasks.save(task);
            }
        });
    }

    @Test
    @DisplayName("finished work is not work in progress: completed assignments leave room under the limit")
    void completedAssignmentsDoNotCount() {
        var owner = account("owner", null);
        var member = account("member", 2);
        Integer board = boardOf(owner, member);
        assign(board, member, 1, 3);

        var status = userService.getWipStatus(owner, member.getId());

        assertThat(status.assignedCount())
                .as("a person whose other tasks are done could otherwise never be assigned another one")
                .isEqualTo(1);
        assertThat(status.withinLimit()).isTrue();

        assign(board, member, 1, 0);
        assertThat(userService.checkWipStatus(member.getId())).isFalse();
    }

    @Test
    @DisplayName("open assignments on every board count, and other people's do not")
    void countsAcrossBoardsForOnePerson() {
        var owner = account("owner", null);
        var member = account("member", 10);
        var other = account("other", 10);
        assign(boardOf(owner, member, other), member, 2, 0);
        Integer second = boardOf(owner, member, other);
        assign(second, member, 1, 0);
        assign(second, other, 4, 0);

        assertThat(userService.getWipStatus(owner, member.getId()).assignedCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("the status of someone the caller shares no board with is a 404, as owner or as member")
    void aStrangerIsNotVisible() {
        var owner = account("owner", null);
        var member = account("member", null);
        var stranger = account("stranger", null);
        boardOf(owner, member);
        boardOf(stranger);

        assertThat(userService.getWipStatus(member, owner.getId()).userId()).isEqualTo(owner.getId());
        assertThat(userService.getWipStatus(owner, member.getId()).userId()).isEqualTo(member.getId());
        for (User caller : new User[] {owner, member}) {
            assertThatThrownBy(() -> userService.getWipStatus(caller, stranger.getId()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.USER_NOT_FOUND);
        }
    }
}
