package pl.myproject.kanbanproject2.board;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;
import java.util.Optional;

@Repository
public interface BoardRepository extends JpaRepository<Board, Integer> {

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

    @EntityGraph(attributePaths = {"owner", "members"})
    Optional<Board> findFirstByOwnerIsNullOrderByIdAsc();

    @EntityGraph(attributePaths = {"owner", "members"})
    Optional<Board> findWithMembersById(Integer id);

    @Query(value = "SELECT role FROM board_members WHERE board_id = :boardId AND user_id = :userId",
            nativeQuery = true)
    Optional<String> findMemberRole(@Param("boardId") Integer boardId, @Param("userId") Integer userId);

    @Query(value = "SELECT user_id, role FROM board_members WHERE board_id = :boardId", nativeQuery = true)
    List<Object[]> findMemberRoles(@Param("boardId") Integer boardId);

    @Modifying
    @Query(value = "UPDATE board_members SET role = :role WHERE board_id = :boardId AND user_id = :userId",
            nativeQuery = true)
    // Callers saveAndFlush the membership first, or this runs before the row exists and updates nothing.
    int updateMemberRole(@Param("boardId") Integer boardId, @Param("userId") Integer userId,
                          @Param("role") String role);
}
