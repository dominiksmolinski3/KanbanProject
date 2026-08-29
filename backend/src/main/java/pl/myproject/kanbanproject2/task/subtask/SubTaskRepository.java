package pl.myproject.kanbanproject2.task.subtask;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.task.Task;

import java.util.List;

@Repository
public interface SubTaskRepository extends JpaRepository<SubTask, Integer> {

    /**
     * The subtasks of one task, or the orphans when {@code task} is {@code null}. Positions are
     * scoped to this list, so the caller needs it to work out the next one.
     */
    List<SubTask> findByTask(Task task);
}
