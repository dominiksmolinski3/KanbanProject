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
     * The highest position in use under one task, or empty when it has none, computed as a
     * database aggregate so a null position simply doesn't take part in the {@code MAX}. No null
     * branch is needed here, unlike the task aggregate, because a subtask can't be an orphan —
     * {@code CreateSubTaskRequest} requires a task.
     */
    @Query("SELECT MAX(subTask.position) FROM SubTask subTask WHERE subTask.task.id = :taskId")
    Optional<Integer> findMaxPosition(@Param("taskId") Integer taskId);

    /** Every subtask on one board, reached through the task that owns it. */
    List<SubTask> findByTaskBoardOrderByIdAsc(Board board);
}
