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

    @Query("SELECT MAX(subTask.position) FROM SubTask subTask WHERE subTask.task.id = :taskId")
    Optional<Integer> findMaxPosition(@Param("taskId") Integer taskId);

    List<SubTask> findByTaskBoardOrderByIdAsc(Board board);
}
