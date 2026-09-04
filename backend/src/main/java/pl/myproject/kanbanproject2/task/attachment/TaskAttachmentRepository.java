package pl.myproject.kanbanproject2.task.attachment;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.task.Task;

import java.util.List;

@Repository
public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, Long> {

    /**
     * One task's attachments, oldest first.
     *
     * <p>The uploader is fetched in the same query because the mapper reads their name on every
     * row, and a lazy to-one read per row is the N+1 the {@code @BatchSize} annotations on
     * {@code Task} exist to answer. One join is cheaper here than a batch, because this is a single
     * collection rather than a collection per task.
     */
    @EntityGraph(attributePaths = "uploadedBy")
    List<TaskAttachment> findByTaskOrderByUploadedAtAscIdAsc(Task task);

    /** Everything hanging off a task, for the cascade when the task itself is deleted. */
    List<TaskAttachment> findByTask(Task task);
}
