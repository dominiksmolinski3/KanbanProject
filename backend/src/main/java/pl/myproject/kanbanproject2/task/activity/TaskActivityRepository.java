package pl.myproject.kanbanproject2.task.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.task.Task;

import java.util.List;

public interface TaskActivityRepository extends JpaRepository<TaskActivity, Integer> {

    Page<TaskActivity> findByBoardOrderByOccurredAtDescIdDesc(Board board, Pageable pageable);

    List<TaskActivity> findByTask(Task task);

    List<TaskActivity> findByBoard(Board board);
}
