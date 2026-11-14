package pl.myproject.kanbanproject2.database;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import pl.myproject.kanbanproject2.board.BoardMapper;
import pl.myproject.kanbanproject2.board.BoardRepository;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.CreateBoardRequest;
import pl.myproject.kanbanproject2.layout.column.ColumnRepository;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistory;
import pl.myproject.kanbanproject2.task.history.TaskColumnHistoryRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(DatabaseTestConfiguration.class)
@Import({BoardService.class, BoardMapper.class})
class HistoryOrderDatabaseTest {

    @Autowired
    private BoardService boardService;

    @Autowired
    private BoardRepository boards;

    @Autowired
    private UserRepository users;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ColumnRepository columns;

    @Autowired
    private TaskColumnHistoryRepository histories;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    @DisplayName("a card's next move is numbered one past its highest, and another card's moves do not count")
    void theNextMoveFollowsTheHighest() {
        transactions.executeWithoutResult(status -> {
            var owner = new User("owner", "owner-" + UUID.randomUUID() + "@example.test", "hashed");
            owner.setEnabled(true);
            users.save(owner);
            var board = boards.findWithMembersById(boardService.createBoard(owner, new CreateBoardRequest("Work")).id())
                    .orElseThrow();
            var column = columns.findByBoardOrderByPositionAsc(board).getFirst();
            var moved = new Task();
            moved.setTitle("moved");
            moved.setBoard(board);
            tasks.save(moved);
            var other = new Task();
            other.setTitle("other");
            other.setBoard(board);
            tasks.save(other);

            assertThat(histories.nextHistoryOrder(moved)).isZero();

            for (Integer order : new Integer[] {0, 4, 2}) {
                var entry = new TaskColumnHistory(moved, column);
                entry.setHistoryOrder(order);
                histories.saveAndFlush(entry);
            }
            histories.saveAndFlush(new TaskColumnHistory(other, column));

            assertThat(histories.nextHistoryOrder(moved)).isEqualTo(5);
            assertThat(histories.nextHistoryOrder(other)).isEqualTo(1);
        });
    }
}
