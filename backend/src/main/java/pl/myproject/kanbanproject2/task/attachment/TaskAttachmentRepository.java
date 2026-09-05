package pl.myproject.kanbanproject2.task.attachment;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.board.Board;
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

    /**
     * How many attachments are on the board, across every one of its tasks - one aggregate rather
     * than loading every row to count them in Java, which is what the quota check in
     * {@code TaskAttachmentService.upload} reads before a blob is written.
     */
    @Query("SELECT COUNT(a) FROM TaskAttachment a WHERE a.task.board = :board")
    long countByTaskBoard(@Param("board") Board board);

    /**
     * The board's attachments, summed in bytes. {@code COALESCE} because {@code SUM} over no rows
     * is {@code NULL}, and a board with nothing on it yet is zero bytes, not an absent quota.
     */
    @Query("SELECT COALESCE(SUM(a.sizeBytes), 0) FROM TaskAttachment a WHERE a.task.board = :board")
    long totalSizeBytesByTaskBoard(@Param("board") Board board);
}
