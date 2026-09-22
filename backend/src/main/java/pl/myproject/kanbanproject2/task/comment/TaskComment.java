package pl.myproject.kanbanproject2.task.comment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;

/**
 * One thing somebody said about one card. It has no board of its own, for the reason an attachment
 * has none: the task carries the board, so a caller may see a comment exactly when they may see the
 * task, and there is no second column that could drift from the first. No {@code @Version}: the
 * only edit is its author rewriting their own words, and the last rewrite is the one they meant.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "task_comments")
public class TaskComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    /**
     * Who wrote it. Nullable because an account can be deleted while the card it commented on is
     * still in use - the comment belongs to the card's record, not to the person.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private User author;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Null until the author rewrites it, so the thread can say which comments were changed. */
    @Column(name = "edited_at")
    private Instant editedAt;
}
