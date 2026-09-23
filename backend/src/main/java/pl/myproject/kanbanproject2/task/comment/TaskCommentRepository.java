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

    /**
     * One card's thread, newest first, paged - the order {@code idx_task_comments_task} is built
     * in. The author is fetched in the same query because the mapper reads their name on every row.
     */
    @EntityGraph(attributePaths = "author")
    Page<TaskComment> findByTaskOrderByCreatedAtDescIdDesc(Task task, Pageable pageable);

    /** Everything said about one card, for removing it with the card. */
    List<TaskComment> findByTask(Task task);

    /** Everything said about a set of cards, for taking a whole board apart at once. */
    List<TaskComment> findByTaskIn(List<Task> tasks);
}
