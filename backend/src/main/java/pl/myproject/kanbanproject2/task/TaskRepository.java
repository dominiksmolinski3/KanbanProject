package pl.myproject.kanbanproject2.task;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
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
 */
@Repository
public interface TaskRepository extends JpaRepository<Task, Integer> {

    @Override
    @EntityGraph(attributePaths = {"column", "row", "parentTask"})
    List<Task> findAll();

    @EntityGraph(attributePaths = {"column", "row", "parentTask"})
    List<Task> findAllByDeadlineIsNotNull();

    @EntityGraph(attributePaths = {"column", "row", "parentTask"})
    List<Task> findAllByDailyFocusTrue();

    /**
     * The tasks in one cell of the board. A {@code null} argument means exactly what it means on
     * the board — the backlog column, or no swimlane — and Spring Data turns it into {@code IS
     * NULL} rather than an equality that can never match.
     */
    @EntityGraph(attributePaths = {"column", "row", "parentTask"})
    List<Task> findByColumnAndRow(Column column, Row row);

    /**
     * Every label in use, as a projection.
     *
     * <p>This used to load every task row and every task's label collection with it, to fold a set
     * together in Java. The database answers it with one query over the join table, and the answer
     * is a handful of short strings rather than the whole board.
     */
    @Query("SELECT DISTINCT label FROM Task task JOIN task.labels label")
    Set<String> findDistinctLabels();

    /**
     * The highest position in use in one cell, or empty when the cell is empty.
     *
     * <p>Scoping positions to their cell was the right fix by the wrong route: it fetched the
     * cell's tasks and folded them in Java, so computing one number got more expensive exactly as
     * a column filled up. The aggregate belongs in the database.
     *
     * <p>The null branches are the whole reason this is written out rather than left as
     * {@code task.column = :column AND task.row = :row}. A null argument means what it means on the
     * board - the backlog, or no swimlane - and an equality against a null bind is never true in
     * SQL, so that form answered "empty cell" for every cell without a swimlane and handed out
     * position 1 forever. A derived query would have written {@code IS NULL} on its own; this one
     * has to say so. Ids rather than entities because an id is what the comparison needs, and
     * {@code :columnId IS NULL} has a type Hibernate can infer.
     */
    @Query("""
            SELECT MAX(task.position) FROM Task task
            WHERE ((:columnId IS NULL AND task.column IS NULL) OR task.column.id = :columnId)
              AND ((:rowId IS NULL AND task.row IS NULL) OR task.row.id = :rowId)
            """)
    Optional<Integer> findMaxPosition(@Param("columnId") Integer columnId, @Param("rowId") Integer rowId);
}
