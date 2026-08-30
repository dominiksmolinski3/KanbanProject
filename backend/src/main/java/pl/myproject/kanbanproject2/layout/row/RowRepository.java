package pl.myproject.kanbanproject2.layout.row;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.board.Board;

import java.util.List;
import java.util.Optional;

@Repository
public interface RowRepository extends JpaRepository<Row, Integer> {

    /** The swimlanes of one board. See {@link pl.myproject.kanbanproject2.layout.column.ColumnRepository}. */
    List<Row> findByBoardOrderByPositionAsc(Board board);

    @Query("SELECT MAX(row.position) FROM Row row WHERE row.board = :board")
    Optional<Integer> findMaxPosition(Board board);
}
