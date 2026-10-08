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

    // FOR UPDATE, not @Lock(PESSIMISTIC_WRITE): Hibernate renders that as FOR NO KEY UPDATE, which a foreign-key check does not wait on.
    @Query(value = "SELECT id FROM columns WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Integer> lockForDelete(Integer id);
}
