package pl.myproject.kanbanproject2.task.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.task.Task;

import java.util.List;

@Repository
public interface TaskCommentRepository extends JpaRepository<TaskComment, Long> {

    @EntityGraph(attributePaths = "author")
    Page<TaskComment> findByTaskOrderByCreatedAtDescIdDesc(Task task, Pageable pageable);

    List<TaskComment> findByTask(Task task);

    List<TaskComment> findByTaskIn(List<Task> tasks);
}
