package pl.myproject.kanbanproject2.task.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.task.Task;

import java.util.List;

public interface TaskActivityRepository extends JpaRepository<TaskActivity, Integer> {

    /**
     * The feed. Ordered by instant then id, since a batch written in one transaction ties on the
     * instant and any tie makes paging skip or repeat rows, silently.
     */
    Page<TaskActivity> findByBoardOrderByOccurredAtDescIdDesc(Board board, Pageable pageable);

    /** Deleting a task detaches its entries rather than taking them; deleting a board takes them. */
    List<TaskActivity> findByTask(Task task);

    List<TaskActivity> findByBoard(Board board);
}
