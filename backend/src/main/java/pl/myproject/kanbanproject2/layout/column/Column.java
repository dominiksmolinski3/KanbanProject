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
    private String name;
    private Integer position;
    @jakarta.persistence.Column(name = "wip_limit")
    private Integer wipLimit;
    /*
     * The board this stage belongs to, and the only thing that decides who may see it. Not
     * nullable: a column with no board is a column nobody owns, which is the state this whole
     * change exists to remove.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;
    /*
     * Batched for the same reason the collections on Task are: ColumnMapper renders every task in
     * every column, so without this the board listing loads one column's tasks per query - and then
     * batches each of those little lists separately, which is where most of the queries went.
     */
    @OneToMany(mappedBy = "column", cascade = CascadeType.ALL)
    @BatchSize(size = 50)
    List<Task> tasks;
}
