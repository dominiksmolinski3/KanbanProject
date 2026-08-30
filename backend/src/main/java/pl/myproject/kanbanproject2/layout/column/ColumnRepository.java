package pl.myproject.kanbanproject2.layout.column;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.board.Board;

import java.util.List;
import java.util.Optional;

@Repository
public interface ColumnRepository extends JpaRepository<Column, Integer> {

    /**
     * The stages of one board, in the order they are meant to read.
     *
     * <p>Every listing is scoped this way now. {@code findAll()} still exists on the interface, and
     * calling it would hand one caller every board in the deployment, which is the bug this whole
     * change is about — {@code BoardScopedQueriesTest} fails the build if a service reaches for it.
     */
    List<Column> findByBoardOrderByPositionAsc(Board board);

    @Query("SELECT MAX(column.position) FROM Column column WHERE column.board = :board")
    Optional<Integer> findMaxPosition(Board board);
}
