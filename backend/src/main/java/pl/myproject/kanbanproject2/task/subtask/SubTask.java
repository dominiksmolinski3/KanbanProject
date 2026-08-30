package pl.myproject.kanbanproject2.task.subtask;

import jakarta.persistence.*;
import jakarta.persistence.Column;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pl.myproject.kanbanproject2.task.Task;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "subtasks")
public class SubTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    /** Optimistic lock; see {@link pl.myproject.kanbanproject2.task.Task#getVersion()}. */
    @Version
    private Integer version;
    @Column(columnDefinition = "TEXT")
    private String title;
    private String description;
    private boolean completed;
    private Integer position;
    @ManyToOne
    @JoinColumn(name = "task_id")
    private Task task;

}

