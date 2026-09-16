package pl.myproject.kanbanproject2.task.activity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.user.User;

import java.time.LocalDateTime;

/**
 * One thing that happened to one task, and who did it. {@code task_column_history} already
 * records moves as an interval series with no actor, so it doesn't replace this table — both are
 * written from the same method in {@code TaskService}, which is what keeps them from drifting.
 * {@code taskTitle} and {@code actorName} are copies rather than references, since an entry that
 * resolved names on read would misrepresent a renamed task and lose everything once the task
 * itself is deleted.
 */
@NoArgsConstructor
@Setter
@Getter
@Entity
@Table(name = "task_activity")
public class TaskActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    /** Null once the task is gone. The entry stays; see {@link #taskTitle}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private Task task;

    @jakarta.persistence.Column(name = "task_title", nullable = false)
    private String taskTitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @jakarta.persistence.Column(name = "actor_name")
    private String actorName;

    @Enumerated(EnumType.STRING)
    @jakarta.persistence.Column(nullable = false, length = 24)
    private TaskActivityType type;

    /** The column moved to, or the person assigned. Null for the types that need no object. */
    @jakarta.persistence.Column(name = "detail")
    private String detail;

    @jakarta.persistence.Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    public TaskActivity(Board board, Task task, User actor, TaskActivityType type, String detail) {
        this.board = board;
        this.task = task;
        this.taskTitle = titleOf(task);
        this.actor = actor;
        this.actorName = actor == null ? null : actor.getName();
        this.type = type;
        this.detail = detail;
    }

    /**
     * A task has no NOT NULL on its title, so the stand-in is chosen at write time — a marker
     * rather than a sentence, since the client renders it in whichever of nine languages it's
     * showing.
     */
    private static String titleOf(Task task) {
        String title = task == null ? null : task.getTitle();
        return title == null || title.isBlank() ? "" : title;
    }
}
