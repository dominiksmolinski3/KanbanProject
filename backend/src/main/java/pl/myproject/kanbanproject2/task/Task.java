package pl.myproject.kanbanproject2.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.layout.column.Column;
import pl.myproject.kanbanproject2.layout.row.Row;
import pl.myproject.kanbanproject2.task.subtask.SubTask;
import pl.myproject.kanbanproject2.user.User;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
@Entity
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    /*
     * Optimistic lock. The board is edited by every member at once and the client sends one
     * position PATCH per card in a reordered cell, so two people dragging in the same column race
     * by construction. Without this the second write silently wins; with it the stale transaction
     * fails its UPDATE ... WHERE version = ? and the caller is told 409 instead of losing the move.
     */
    @Version
    private Integer version;
    private String title;
    private Integer position;
    private boolean completed;
    private String description;
    @jakarta.persistence.Column(name = "deadline")
    private LocalDateTime deadline;
    @jakarta.persistence.Column(name = "expired")
    private boolean expired = false;
    @jakarta.persistence.Column(name = "daily_focus")
    private boolean dailyFocus = false;
    /*
     * Fetching, deliberately.
     *
     * The to-one associations are LAZY because @ManyToOne defaults to EAGER, and the default made
     * every listing fetch a column and a row per task whether or not anything read them. What does
     * read them is TaskMapper, and it reads only getId() - so the ones a listing genuinely needs
     * are named in an @EntityGraph on the query instead, which fetches them in one join rather
     * than one query each.
     *
     * The collections were already LAZY and were the other half of the problem: the mapper touches
     * users and childTasks on every task, which is a query each. @BatchSize turns that into one
     * query per fifty tasks. It is the right shape here precisely because they cannot all be
     * joined in the same query - two collection joins multiply into a cartesian product, and
     * these are Sets, so Hibernate would let it happen rather than refusing.
     */
    @ElementCollection
    @CollectionTable(name = "task_labels", joinColumns = @JoinColumn(name = "task_id"))
    @jakarta.persistence.Column(name = "label")
    @BatchSize(size = 50)
    private Set<String> labels;
    /*
     * The board is carried on the task itself rather than read through the column, because the
     * column is nullable - a task can be taken off the board and still exist - and a task with no
     * column would then have no owner at all. It is set once, from the board the task is created
     * on, and TaskService refuses any move that would put the task in a column or row belonging to
     * a different one.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "column_id")
    private Column column;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "row_id")
    private Row row;
    @ManyToMany
    @JoinTable(
            name = "user_task",
            joinColumns = @JoinColumn(name = "task_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id")
    )
    @JsonIgnoreProperties("tasks")
    @BatchSize(size = 50)
    private Set<User> users = new HashSet<>();

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 50)
    private List<SubTask> subTasks = new ArrayList<>();
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_task_id")
    @JsonIgnoreProperties("childTasks")
    private Task parentTask;

    @OneToMany(mappedBy = "parentTask", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JsonIgnoreProperties("parentTask")
    @BatchSize(size = 50)
    private Set<Task> childTasks = new HashSet<>();
}
