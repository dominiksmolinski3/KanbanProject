package pl.myproject.kanbanproject2.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.row.Row;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface TaskRepository extends JpaRepository<Task, Integer> {

    @Modifying
    @Query("update Task t set t.row = null where t.row = :row")
    int detachFromRow(@Param("row") Row row);

    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByBoardOrderByIdAsc(Board board);

    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByBoardAndDailyFocusTrue(Board board);

    @Query(value = """
            SELECT id FROM task
            WHERE deadline IS NOT NULL
              AND COALESCE(expired, FALSE) <> (deadline < :now)
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Integer> claimTasksCrossingDeadline(@Param("now") LocalDateTime now);

    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByBoardAndColumnAndRow(Board board, Column column, Row row);

    @Query("SELECT DISTINCT label FROM Task task JOIN task.labels label WHERE task.board = :board")
    Set<String> findDistinctLabels(Board board);


    @Query(value = """
            SELECT DISTINCT task.id FROM Task task
            LEFT JOIN task.labels label
            LEFT JOIN task.users assignee
            WHERE task.board = :board
              AND (:ignoreText = TRUE OR LOWER(task.title) LIKE :text ESCAPE '!'
                                      OR LOWER(task.description) LIKE :text ESCAPE '!')
              AND (:ignoreCompleted = TRUE OR task.completed = :completed)
              AND (:ignoreDeadlineFrom = TRUE OR task.deadline >= :deadlineFrom)
              AND (:ignoreDeadlineTo = TRUE OR task.deadline <= :deadlineTo)
              AND (:ignoreLabels = TRUE OR label IN :labels)
              AND (:ignoreAssignees = TRUE OR assignee.id IN :assignees)
            ORDER BY task.id ASC
            """,
            countQuery = """
            SELECT COUNT(DISTINCT task.id) FROM Task task
            LEFT JOIN task.labels label
            LEFT JOIN task.users assignee
            WHERE task.board = :board
              AND (:ignoreText = TRUE OR LOWER(task.title) LIKE :text ESCAPE '!'
                                      OR LOWER(task.description) LIKE :text ESCAPE '!')
              AND (:ignoreCompleted = TRUE OR task.completed = :completed)
              AND (:ignoreDeadlineFrom = TRUE OR task.deadline >= :deadlineFrom)
              AND (:ignoreDeadlineTo = TRUE OR task.deadline <= :deadlineTo)
              AND (:ignoreLabels = TRUE OR label IN :labels)
              AND (:ignoreAssignees = TRUE OR assignee.id IN :assignees)
            """)
    Page<Integer> findMatchingIds(@Param("board") Board board,
                                  @Param("ignoreText") boolean ignoreText,
                                  @Param("text") String text,
                                  @Param("ignoreCompleted") boolean ignoreCompleted,
                                  @Param("completed") boolean completed,
                                  @Param("ignoreDeadlineFrom") boolean ignoreDeadlineFrom,
                                  @Param("deadlineFrom") LocalDateTime deadlineFrom,
                                  @Param("ignoreDeadlineTo") boolean ignoreDeadlineTo,
                                  @Param("deadlineTo") LocalDateTime deadlineTo,
                                  @Param("ignoreLabels") boolean ignoreLabels,
                                  @Param("labels") Collection<String> labels,
                                  @Param("ignoreAssignees") boolean ignoreAssignees,
                                  @Param("assignees") Collection<Integer> assignees,
                                  Pageable pageable);

    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByIdIn(Collection<Integer> ids);

    @Query("""
            SELECT MAX(task.position) FROM Task task
            WHERE task.board.id = :boardId
              AND ((:columnId IS NULL AND task.column IS NULL) OR task.column.id = :columnId)
              AND ((:rowId IS NULL AND task.row IS NULL) OR task.row.id = :rowId)
            """)
    Optional<Integer> findMaxPosition(@Param("boardId") Integer boardId,
                                      @Param("columnId") Integer columnId,
                                      @Param("rowId") Integer rowId);
}
