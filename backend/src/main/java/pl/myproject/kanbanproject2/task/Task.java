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
    // Optimistic lock: a reordered cell sends one position PATCH per card, so concurrent drags race
    // by construction. Without this the second write silently wins; with it the stale transaction
    // fails and the caller gets a 409 instead of losing the move.
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
    // To-one associations are LAZY because @ManyToOne defaults to EAGER, which fetched a column and
    // row per task whether or not anything read them; the ones a listing needs are named in an
    // @EntityGraph instead. Collections use @BatchSize rather than being joined, since two Set
    // joins would multiply into a cartesian product Hibernate won't refuse on its own.
    @ElementCollection
    @CollectionTable(name = "task_labels", joinColumns = @JoinColumn(name = "task_id"))
    @jakarta.persistence.Column(name = "label")
    @BatchSize(size = 50)
    private Set<String> labels;
    // Carried on the task itself, not read through the column, because the column is nullable — a
    // task removed from the board would otherwise have no owner at all. TaskService refuses any
    // move that would put the task in a column or row on a different board.
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
