package pl.myproject.kanbanproject2.task.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

public interface TaskActivityRepository extends JpaRepository<TaskActivity, Integer> {

    Page<TaskActivity> findByBoardOrderByOccurredAtDescIdDesc(Board board, Pageable pageable);

    List<TaskActivity> findByTask(Task task);

    List<TaskActivity> findByBoard(Board board);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE TaskActivity activity SET activity.actor = null WHERE activity.actor = :user")
    int forgetActor(@Param("user") User user);
}
