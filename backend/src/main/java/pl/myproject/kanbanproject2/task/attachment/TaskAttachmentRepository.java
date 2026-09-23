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

    @EntityGraph(attributePaths = "uploadedBy")
    List<TaskAttachment> findByTaskOrderByUploadedAtAscIdAsc(Task task);

    List<TaskAttachment> findByTask(Task task);

    List<TaskAttachment> findByTaskIn(List<Task> tasks);

    @Query("SELECT COUNT(a) FROM TaskAttachment a WHERE a.task.board = :board")
    long countByTaskBoard(@Param("board") Board board);

    @Query("SELECT COALESCE(SUM(a.sizeBytes), 0) FROM TaskAttachment a WHERE a.task.board = :board")
    long totalSizeBytesByTaskBoard(@Param("board") Board board);
}
