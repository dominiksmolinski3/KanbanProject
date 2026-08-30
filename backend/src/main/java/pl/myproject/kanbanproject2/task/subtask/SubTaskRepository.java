package pl.myproject.kanbanproject2.task.subtask;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.board.Board;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubTaskRepository extends JpaRepository<SubTask, Integer> {

    /**
     * The highest position in use under one task, or empty when it has no subtasks yet.
     *
     * <p>This used to fetch the parent's subtasks and fold them in Java - the shape
     * {@link pl.myproject.kanbanproject2.task.TaskRepository#findMaxPosition} moved away from, kept
     * only because a subtask list is short. A null position does not take part in a MAX, so the
     * rows written before positions were scoped are skipped rather than filtered in Java.
     *
     * <p>No null branch here, unlike the task aggregate, because a subtask cannot be an orphan:
     * {@code CreateSubTaskRequest} requires a task, since a subtask reads its board through one.
     */
    @Query("SELECT MAX(subTask.position) FROM SubTask subTask WHERE subTask.task.id = :taskId")
    Optional<Integer> findMaxPosition(@Param("taskId") Integer taskId);

    /** Every subtask on one board, reached through the task that owns it. */
    List<SubTask> findByTaskBoardOrderByIdAsc(Board board);
}
