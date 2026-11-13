package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardMapper;
import pl.myproject.kanbanproject2.board.BoardRepository;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.CreateBoardRequest;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserMapper;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringJUnitConfig(DatabaseTestConfiguration.class)
@Import({BoardService.class, BoardMapper.class, UserService.class, UserMapper.class})
class BoardDeletionDatabaseTest {

    @Autowired
    private BoardService boardService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository users;

    @Autowired
    private BoardRepository boards;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private User account(String name) {
        return transactions.execute(status -> {
            var user = new User(name, name + "-" + UUID.randomUUID() + "@example.test", "hashed");
            user.setEnabled(true);
            return users.save(user);
        });
    }

    private Integer boardWithTasks(User owner, int plainTasks, boolean withSubtaskTree) {
        return transactions.execute(status -> {
            var board = boards.findWithMembersById(boardService.createBoard(owner, new CreateBoardRequest("Work")).id())
                    .orElseThrow();
            for (int i = 0; i < plainTasks; i++) {
                tasks.save(task("card " + i, board, null));
            }
            if (withSubtaskTree) {
                var parent = tasks.save(task("parent", board, null));
                tasks.save(task("first child", board, parent));
                tasks.save(task("second child", board, parent));
            }
            return board.getId();
        });
    }

    private static Task task(String title, Board board, Task parent) {
        var task = new Task();
        task.setTitle(title);
        task.setBoard(board);
        task.setParentTask(parent);
        return task;
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    @Test
    @DisplayName("a board holding several tasks can be deleted")
    void aBoardWithSeveralTasksCanBeDeleted() {
        var owner = account("owner");
        Integer board = boardWithTasks(owner, 3, false);

        assertThatCode(() -> boardService.deleteBoard(owner, board)).doesNotThrowAnyException();

        assertThat(count("select count(*) from boards where id = ?", board)).isZero();
        assertThat(count("select count(*) from task where board_id = ?", board)).isZero();
    }

    @Test
    @DisplayName("a board whose tasks have child tasks can be deleted")
    void aBoardWithChildTasksCanBeDeleted() {
        var owner = account("owner");
        Integer board = boardWithTasks(owner, 2, true);

        assertThatCode(() -> boardService.deleteBoard(owner, board)).doesNotThrowAnyException();

        assertThat(count("select count(*) from boards where id = ?", board)).isZero();
        assertThat(count("select count(*) from task where board_id = ?", board)).isZero();
    }

    @Test
    @DisplayName("an account whose only board holds several tasks can be closed")
    void theOwnerOfSuchABoardCanCloseTheirAccount() {
        var owner = account("owner");
        Integer board = boardWithTasks(owner, 3, true);

        assertThatCode(() -> userService.deleteUser(owner.getId())).doesNotThrowAnyException();

        assertThat(count("select count(*) from users where id = ?", owner.getId())).isZero();
        assertThat(count("select count(*) from boards where id = ?", board)).isZero();
    }
}
