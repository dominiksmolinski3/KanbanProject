package pl.myproject.kanbanproject2.task;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.row.Row;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code @EntityGraph} on the listings names the to-one associations {@link TaskMapper} reads.
 *
 * <p>They are {@code LAZY} on the entity, which is right for the paths that never touch them; every
 * listing here does touch them, so naming them fetches them alongside the tasks in one join instead
 * of one query per task. The collections are deliberately not named: joining two of them in the
 * same query multiplies rows into a cartesian product, and because they are {@code Set}s Hibernate
 * would allow it rather than refusing. {@code @BatchSize} on the entity covers those instead.
 *
 * <p>Every listing is also scoped to one board. That is the authorization boundary rather than a
 * convenience: an unscoped listing hands the caller every task in the deployment, which is what
 * {@code findAll()} used to do here.
 */
@Repository
public interface TaskRepository extends JpaRepository<Task, Integer> {

    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByBoardOrderByIdAsc(Board board);

    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByBoardAndDailyFocusTrue(Board board);

    /**
     * Every task with a deadline, across every board.
     *
     * <p>The one query here that is deliberately not board-scoped. It backs the scheduled sweep,
     * which runs as the system rather than as a caller and has to see the whole deployment; there
     * is no user on whose behalf it could be narrowed.
     */
    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findAllByDeadlineIsNotNull();

    /**
     * The tasks in one cell of the board. A {@code null} argument means exactly what it means on
     * the board — the backlog column, or no swimlane — and Spring Data turns it into {@code IS
     * NULL} rather than an equality that can never match.
     */
    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByBoardAndColumnAndRow(Board board, Column column, Row row);

    /**
     * Every label in use on one board, as a projection.
     *
     * <p>This used to load every task row and every task's label collection with it, to fold a set
     * together in Java. The database answers it with one query over the join table, and the answer
     * is a handful of short strings rather than the whole board.
     */
    @Query("SELECT DISTINCT label FROM Task task JOIN task.labels label WHERE task.board = :board")
    Set<String> findDistinctLabels(Board board);

    /**
     * The highest position in use in one cell, or {@code null} when the cell is empty.
     *
     * <p>Scoping positions to their cell was the right fix by the wrong route: it fetched the
     * cell's tasks and folded them in Java, so computing one number got more expensive exactly as
     * a column filled up. The aggregate belongs in the database. The board is part of the key
     * because a task can sit in no column and no swimlane, and without it two boards' loose tasks
     * would be handing each other positions.
     */
    @Query("""
            SELECT MAX(task.position) FROM Task task
            WHERE task.board = :board AND task.column = :column AND task.row = :row
            """)
    Optional<Integer> findMaxPosition(Board board, Column column, Row row);
}
