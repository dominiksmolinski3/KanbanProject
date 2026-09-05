package pl.myproject.kanbanproject2.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * The ids of the tasks on one board that match a search, one page at a time.
     *
     * <p><b>Ids, and then a second query for the rows.</b> A single paged query that also fetched
     * the to-one associations would be pagination over a join whose rows multiply - the label and
     * assignee joins below are what the facets filter on, and a task with three labels is three
     * rows before {@code DISTINCT}. Hibernate answers that by paginating in memory and says so in a
     * warning, which means reading the whole matching set to hand back twenty-five of them. Two
     * queries keep the {@code LIMIT} in SQL where it belongs, and the second one is
     * {@link #findByIdIn} with the same entity graph every other listing here uses.
     *
     * <p><b>Every filter is optional, and each is a flag plus a value rather than a nullable
     * one.</b> The obvious form - {@code :param IS NULL OR ...}, which {@link #findMaxPosition}
     * uses - does not work here. A parameter whose only appearance is {@code ? IS NULL} gives
     * PostgreSQL nothing to infer a type from, and the query fails at runtime with
     * {@code could not determine data type of parameter}; {@code findMaxPosition} escapes it only
     * because each of its parameters is also compared against a typed column. <b>Nothing in this
     * repository could have caught that</b> - {@code QueryStringsResolveTest} compiles HQL rather
     * than executing SQL, and every service test mocks this interface - so it was found by running
     * a search against a real database, and the shape below is what it left behind. The collection
     * facets needed a flag anyway, for a different reason: a bare {@code IN} against an empty list
     * is not a clause that is skipped, it is a clause that matches nothing.
     *
     * <p><b>{@code ESCAPE '!'} is not decoration.</b> The pattern is built from something a person
     * typed, and without it a search for {@code 100%} is a pattern that matches the whole board.
     * {@code TaskSearchCriteria.likePattern()} does the escaping; this is the half of that
     * agreement the database has to be told about.
     *
     * <p>Ordered by id, which is creation order and is stable. Any order that ties would make
     * paging skip and repeat rows across pages, silently, and only on boards big enough to page.
     */
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

    /**
     * The rows behind one page of {@link #findMatchingIds}, with the associations the mapper reads.
     *
     * <p>Not board-scoped, and that is safe only because of how it is called: the ids come from
     * {@link #findMatchingIds}, which is scoped, so this never widens what the caller can see. It
     * is deliberately not a route's entry point for that reason.
     */
    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByIdIn(Collection<Integer> ids);

    /**
     * The highest position in use in one cell, or empty when the cell is empty.
     *
     * <p>Scoping positions to their cell was the right fix by the wrong route: it fetched the
     * cell's tasks and folded them in Java, so computing one number got more expensive exactly as
     * a column filled up. The aggregate belongs in the database.
     *
     * <p>The board is part of the key because a task can sit in no column and no swimlane, and
     * without it two boards' loose tasks would be handing each other positions.
     *
     * <p>The null branches on the other two are the whole reason this is written out rather than
     * left as {@code task.column = :column AND task.row = :row}. A null argument means what it
     * means on the board - the backlog, or no swimlane - and an equality against a null bind is
     * never true in SQL, so that form answered "empty cell" for every cell without a swimlane and
     * handed out position 1 forever. A derived query would have written {@code IS NULL} on its own;
     * this one has to say so. The board takes no such branch because a task always has one - the
     * column on {@code Task} is {@code NOT NULL} since V5.
     *
     * <p>Ids rather than entities because an id is what the comparison needs, and
     * {@code :columnId IS NULL} has a type Hibernate can infer.
     */
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
