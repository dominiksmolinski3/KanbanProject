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
    @ElementCollection
    @CollectionTable(name = "task_labels", joinColumns = @JoinColumn(name = "task_id"))
    @jakarta.persistence.Column(name = "label")
    @BatchSize(size = 50)
    private Set<String> labels;
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
