package pl.myproject.kanbanproject2.layout.column;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.task.Task;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
@Entity
@Table(name = "columns")
public class Column {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    /** Optimistic lock; see {@link pl.myproject.kanbanproject2.task.Task#getVersion()}. */
    @Version
    private Integer version;
    private String name;
    private Integer position;
    @jakarta.persistence.Column(name = "wip_limit")
    private Integer wipLimit;
    /** The board this stage belongs to, and the only thing that decides who may see it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;
    /*
     * Batched for the same reason the collections on Task are: ColumnMapper renders every task in
     * every column, so without this the board listing loads one column's tasks per query.
     */
    @OneToMany(mappedBy = "column", cascade = CascadeType.ALL)
    @BatchSize(size = 50)
    List<Task> tasks;
}
