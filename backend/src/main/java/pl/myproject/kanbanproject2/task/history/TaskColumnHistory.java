package pl.myproject.kanbanproject2.task.history;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import pl.myproject.kanbanproject2.layout.column.BoardColumn;
import pl.myproject.kanbanproject2.task.Task;

import java.time.Instant;

@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
@Entity
@Table(name = "task_column_history")
public class TaskColumnHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;

    @ManyToOne
    @JoinColumn(name = "column_id")
    private BoardColumn column;

    private String columnName;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "history_order", nullable = false)
    private Integer historyOrder;

    public TaskColumnHistory(Task task, BoardColumn column) {
        this.task = task;
        this.column = column;
        this.columnName = column.getName();
        this.changedAt = Instant.now();
        this.historyOrder = 0;
    }
}