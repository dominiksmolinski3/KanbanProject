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

/**
 * The {@code @EntityGraph} on the listings fetches the to-one associations {@link TaskMapper}
 * reads in one join instead of one query per task; the collections are deliberately left out since
 * joining two {@code Set}s multiplies rows into a cartesian product, so {@code @BatchSize} on the
 * entity covers those instead. Every listing is also scoped to one board — the authorization
 * boundary, not a convenience: an unscoped listing hands the caller every task in the deployment,
 * which is what {@code findAll()} used to do here.
 */
@Repository
public interface TaskRepository extends JpaRepository<Task, Integer> {

    /**
     * Takes every task out of a swimlane that is about to be deleted, in one statement and with no
     * version check. The per-task save this replaced bumped each task's {@code @Version}, so a row
     * deleted while a column holding the same tasks was being deleted made one of the two a 409 -
     * every time, measured, with the two requests sent together. A delete of the whole row has no
     * stale form to protect against, which is what the version is for; not bumping it is also what
     * lets the column's concurrent delete of the same tasks still match the version it loaded.
     * Not {@code clearAutomatically}: that detaches everything else the caller has loaded, which is
     * how an accepted invitation once became a 500.
     */
    @Modifying
    @Query("update Task t set t.row = null where t.row = :row")
    int detachFromRow(@Param("row") Row row);

    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByBoardOrderByIdAsc(Board board);

    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByBoardAndDailyFocusTrue(Board board);

    /**
     * The ids of the tasks whose {@code expired} flag no longer matches their deadline, locked for
     * the sweep about to write them. Deliberately not board-scoped: the sweep runs as the system,
     * with no caller to narrow it to. Selecting only the rows that disagree (rather than every task
     * with a deadline) is what makes the lock affordable, and {@code FOR UPDATE SKIP LOCKED} is what
     * makes a second replica safe — it partitions the work instead of double-mailing every assignee.
     * The lock lasts as long as the transaction, which is why {@code TaskService} is
     * {@code @Transactional} ({@code DeadlineSweepClaimTest} asserts it), and {@code COALESCE}
     * reconciles the nullable column with the entity's primitive default. Native, because
     * {@code SKIP LOCKED} has no JPQL spelling — {@code QueryStringsResolveTest} skips native
     * queries, so nothing but a database checks this string.
     */
    @Query(value = """
            SELECT id FROM task
            WHERE deadline IS NOT NULL
              AND COALESCE(expired, FALSE) <> (deadline < :now)
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Integer> claimTasksCrossingDeadline(@Param("now") LocalDateTime now);

    /**
     * The tasks in one cell of the board. A {@code null} argument means exactly what it means on
     * the board — the backlog column, or no swimlane — and Spring Data turns it into {@code IS
     * NULL} rather than an equality that can never match.
     */
    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByBoardAndColumnAndRow(Board board, Column column, Row row);

    /**
     * Every label in use on one board, as a projection over the join table rather than loading
     * every task and its label collection to fold a set together in Java.
     */
    @Query("SELECT DISTINCT label FROM Task task JOIN task.labels label WHERE task.board = :board")
    Set<String> findDistinctLabels(Board board);


    /**
     * The ids of the tasks on one board that match a search, one page at a time. Ids first, then a
     * second query for the rows: paginating a join directly multiplies rows before
     * {@code DISTINCT} (a task with three labels is three rows), which forces Hibernate to paginate
     * in memory; two queries keep the {@code LIMIT} in SQL, and the second is {@link #findByIdIn}.
     * Every filter is a flag plus a value rather than a nullable parameter, because a bind used only
     * in {@code ? IS NULL} gives PostgreSQL no type to infer and fails at runtime (unlike
     * {@link #findMaxPosition}, whose parameters are also compared against a typed column) — found
     * only by running a search against a real database, since {@code QueryStringsResolveTest}
     * compiles HQL rather than executing it and every service test mocks this interface. The
     * collection facets need a flag anyway: a bare {@code IN} against an empty list matches nothing
     * rather than being skipped. {@code ESCAPE '!'} stops a literal {@code 100%} from being read as
     * a wildcard, and ordering by id keeps paging stable — any tie would skip or repeat rows across
     * pages.
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
     * Not board-scoped itself — safe only because both callers already scope their own ids:
     * {@link #findMatchingIds} for search, {@link #claimTasksCrossingDeadline} for the deadline
     * sweep (unscoped for the same reason: it runs as the system, with no caller). Nothing that
     * takes a {@code currentUser} may reach this directly.
     */
    @EntityGraph(attributePaths = {"board", "column", "row", "parentTask"})
    List<Task> findByIdIn(Collection<Integer> ids);

    /**
     * The highest position in use in one cell, computed as an aggregate rather than by fetching the
     * cell's tasks and folding them in Java. The board is part of the key because a task can sit in
     * no column and no swimlane, and the explicit null branches on column/row matter because an
     * equality against a null bind is never true in SQL — without them every swimlane-less cell
     * would read as empty and hand out position 1 forever. Ids rather than entities because
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
