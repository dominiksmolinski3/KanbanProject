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

    List<TaskColumnHistory> findByTaskIn(List<Task> tasks);

    List<TaskColumnHistory> findByColumn(Column column);

    @EntityGraph(attributePaths = {"task", "task.column", "column"})
    @Query("SELECT h FROM TaskColumnHistory h WHERE h.task.board = :board AND h.changedAt < :before "
            + "ORDER BY h.task.id, h.changedAt, h.historyOrder, h.id")
    List<TaskColumnHistory> findBoardHistoryBefore(@Param("board") Board board,
                                                   @Param("before") LocalDateTime before);

}