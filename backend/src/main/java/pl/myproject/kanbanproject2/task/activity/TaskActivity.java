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
 * One thing that happened to one task, and who did it.
 *
 * <p>{@code task_column_history} already records moves, and this does not replace it. The two
 * answer different questions and are shaped for them: the history table is an <em>interval</em>
 * series - one row per arrival, ordered, folded by the task panel into "how long in each column" -
 * and it has never recorded <b>who</b>. An activity feed with no actor is not an activity feed, so
 * that column is the reason this table exists rather than a query over the other one. Both are
 * written from the same method in {@code TaskService}, which is the only thing keeping them from
 * drifting.
 *
 * <p><b>Two fields are copies on purpose.</b> {@code taskTitle} and {@code actorName} are what
 * they were when the entry was written, exactly as {@code TaskColumnHistory.columnName} is. An
 * event log that resolved names on read would show a renamed task under its new title in an entry
 * about the old one - and, worse, would have nothing at all to show once the task is deleted,
 * which is precisely the entry nobody can afford to lose.
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
     * A task has no NOT NULL on its title, and an untitled card is a real thing a person can make.
     * The column here is not null because an entry with no subject is unreadable, so the stand-in
     * is chosen at write time - and it is a marker rather than a sentence, because the client
     * writes the sentence in whichever of nine languages it is showing.
     */
    private static String titleOf(Task task) {
        String title = task == null ? null : task.getTitle();
        return title == null || title.isBlank() ? "" : title;
    }
}
