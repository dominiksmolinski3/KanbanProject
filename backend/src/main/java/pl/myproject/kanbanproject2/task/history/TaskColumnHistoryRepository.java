package pl.myproject.kanbanproject2.task.history;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.task.Task;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TaskColumnHistoryRepository extends JpaRepository<TaskColumnHistory, Integer> {
    List<TaskColumnHistory> findByTaskOrderByChangedAtDesc(Task task);

    /**
     * The history of a set of tasks, for taking a whole board apart at once. {@code task_id} is not
     * nullable and nothing cascades to it, so these rows have to go before the tasks do.
     */
    List<TaskColumnHistory> findByTaskIn(List<Task> tasks);

    /**
     * Entries left pointing at a column after the task that earned them has moved on - {@code
     * ColumnService.deleteColumn} detaches these rather than deleting them, since the task itself
     * (and the rest of its history) is unaffected by the column going away.
     */
    List<TaskColumnHistory> findByColumn(Column column);

    /**
     * Every arrival on a board before {@code before}, grouped by task and in the order they
     * happened - the whole input of the flow metrics. The task, its current column and the column
     * each row points at are fetched in the same query, since the fold reads all three per row.
     * {@code history_order} breaks ties on {@code changed_at}, which two moves in one transaction
     * can share.
     */
    @EntityGraph(attributePaths = {"task", "task.column", "column"})
    @Query("SELECT h FROM TaskColumnHistory h WHERE h.task.board = :board AND h.changedAt < :before "
            + "ORDER BY h.task.id, h.changedAt, h.historyOrder, h.id")
    List<TaskColumnHistory> findBoardHistoryBefore(@Param("board") Board board,
                                                   @Param("before") LocalDateTime before);

}