package pl.myproject.kanbanproject2.board;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;
import java.util.Optional;

@Repository
public interface BoardRepository extends JpaRepository<Board, Integer> {

    /**
     * Every board {@code user} may see, owned or joined, oldest first. {@code DISTINCT} collapses
     * the duplicate rows the membership join produces per member; the owner is matched separately
     * so a board whose owner was somehow dropped from the join table doesn't become invisible to
     * the only account that can fix it.
     */
    @Query("""
            SELECT DISTINCT board FROM Board board
            LEFT JOIN board.members member
            WHERE board.owner = :user OR member = :user
            ORDER BY board.id
            """)
    @EntityGraph(attributePaths = {"owner", "members"})
    List<Board> findVisibleTo(@Param("user") User user);

    @EntityGraph(attributePaths = {"owner", "members"})
    List<Board> findByOwnerOrderByIdAsc(User owner);

    /**
     * The board the V5 migration created for data that predates boards, if it is still unclaimed.
     * There is at most one; ordering makes the query total rather than relying on that.
     */
    @EntityGraph(attributePaths = {"owner", "members"})
    Optional<Board> findFirstByOwnerIsNullOrderByIdAsc();

    @EntityGraph(attributePaths = {"owner", "members"})
    Optional<Board> findWithMembersById(Integer id);
}
