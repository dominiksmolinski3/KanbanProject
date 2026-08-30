package pl.myproject.kanbanproject2.task.history;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.task.Task;

import java.util.List;

@Repository
public interface TaskColumnHistoryRepository extends JpaRepository<TaskColumnHistory, Integer> {
    List<TaskColumnHistory> findByTaskOrderByChangedAtDesc(Task task);

    /**
     * The history of a set of tasks, for taking a whole board apart at once. {@code task_id} is not
     * nullable and nothing cascades to it, so these rows have to go before the tasks do.
     */
    List<TaskColumnHistory> findByTaskIn(List<Task> tasks);

}