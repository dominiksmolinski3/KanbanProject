package pl.myproject.kanbanproject2.layout.column;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.board.Board;

import java.util.List;
import java.util.Optional;

@Repository
public interface ColumnRepository extends JpaRepository<Column, Integer> {

    List<Column> findByBoardOrderByPositionAsc(Board board);

    @Query("SELECT MAX(column.position) FROM Column column WHERE column.board = :board")
    Optional<Integer> findMaxPosition(Board board);
}
