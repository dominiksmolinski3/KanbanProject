package pl.myproject.kanbanproject2.database;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
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
import pl.myproject.kanbanproject2.board.invitation.BoardInvitation;
import pl.myproject.kanbanproject2.board.invitation.BoardInvitationRepository;
import pl.myproject.kanbanproject2.chat.Chat;
import pl.myproject.kanbanproject2.chat.ChatRepository;
import pl.myproject.kanbanproject2.chat.MessageType;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.activity.TaskActivity;
import pl.myproject.kanbanproject2.task.activity.TaskActivityRepository;
import pl.myproject.kanbanproject2.task.activity.TaskActivityType;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistory;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserMapper;
import pl.myproject.kanbanproject2.user.UserRepository;
import pl.myproject.kanbanproject2.user.UserService;

import java.time.Instant;
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
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ColumnRepository columns;

    @Autowired
    private TaskColumnHistoryRepository histories;

    @Autowired
    private TaskActivityRepository activities;

    @Autowired
    private ChatRepository chats;

    @Autowired
    private BoardInvitationRepository invitations;

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
    @DisplayName("a board takes its conversation, activity, card history and invitations with it, and nobody else's")
    void aBoardTakesWhatHangsOffIt() {
        var owner = account("owner");
        Integer doomed = boardWithTasks(owner, 2, false);
        Integer kept = boardWithTasks(owner, 2, false);
        for (Integer boardId : new Integer[] {doomed, kept}) {
            transactions.executeWithoutResult(status -> {
                var board = boards.findWithMembersById(boardId).orElseThrow();
                var column = columns.findByBoardOrderByPositionAsc(board).getFirst();
                for (Task task : tasks.findByBoardOrderByIdAsc(board)) {
                    histories.save(new TaskColumnHistory(task, column));
                    activities.save(new TaskActivity(board, task, null, TaskActivityType.values()[0], null));
                }
                activities.save(new TaskActivity(board, null, null, TaskActivityType.values()[0], "about a card long gone"));
                for (int i = 0; i < 3; i++) {
                    chats.save(Chat.builder().type(MessageType.CHAT).content("hello " + i).sender(owner.getEmail())
                            .board(board).timestamp(Instant.now()).build());
                }
                invitations.save(new BoardInvitation(board, "pending-" + UUID.randomUUID() + "@example.test",
                        users.findById(owner.getId()).orElseThrow()));
            });
        }

        assertThatCode(() -> boardService.deleteBoard(owner, doomed)).doesNotThrowAnyException();

        for (String table : new String[] {"chat_messages", "task_activity", "board_invitations", "task"}) {
            assertThat(count("select count(*) from " + table + " where board_id = ?", doomed)).as(table).isZero();
            assertThat(count("select count(*) from " + table + " where board_id = ?", kept)).as(table).isPositive();
        }
        assertThat(count("select count(*) from task_column_history h join task t on t.id = h.task_id where t.board_id = ?", kept))
                .isEqualTo(2);
        assertThat(count("select count(*) from task_column_history h where not exists (select 1 from task t where t.id = h.task_id)"))
                .isZero();
        assertThat(count("select count(*) from boards where id = ?", doomed)).isZero();
    }

    @Test
    @DisplayName("a board's tasks leave in batches, not in one round trip each")
    void tasksLeaveInBatches() {
        var owner = account("owner");
        Integer board = boardWithTasks(owner, 120, false);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        boardService.deleteBoard(owner, board);

        assertThat(count("select count(*) from task where board_id = ?", board)).isZero();
        assertThat(statistics.getPrepareStatementCount()).isLessThan(60);
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
