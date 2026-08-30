package pl.myproject.kanbanproject2.task.subtask;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubTaskRepository extends JpaRepository<SubTask, Integer> {

    /**
     * The highest position in use under one task, or empty when it has no subtasks yet. A null
     * {@code taskId} scopes it to the orphans, which are their own list on the same footing.
     *
     * <p>This used to fetch the parent's subtasks and fold them in Java - the shape
     * {@link pl.myproject.kanbanproject2.task.TaskRepository#findMaxPosition} moved away from, kept
     * only because a subtask list is short. The null branch is written out for the same reason it
     * is there: an equality against a null bind is never true, so the orphan list would have
     * measured as empty and every orphan would have been numbered 1.
     */
    @Query("""
            SELECT MAX(subTask.position) FROM SubTask subTask
            WHERE (:taskId IS NULL AND subTask.task IS NULL) OR subTask.task.id = :taskId
            """)
    Optional<Integer> findMaxPosition(@Param("taskId") Integer taskId);
}
