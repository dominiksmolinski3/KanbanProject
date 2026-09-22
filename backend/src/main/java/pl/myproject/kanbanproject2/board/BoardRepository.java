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

    /*
     * board_members.role (V21) is native SQL rather than a mapped attribute of Board's own
     * @ManyToMany, for the same reason the outbox claim and the deadline sweep are native: it is
     * data the collection mapping has no use for, so giving it one would mean a second, awkward
     * entity over the same join table for a single extra column. QueryStringsResolveTest compiles
     * hand-written HQL and skips native queries, so nothing but a database checks these strings.
     */

    /** One member's role on one board, or empty for a caller with no row there - the owner, or
     * nobody at all. {@link BoardService#roleOf} is what decides what that emptiness means. */
    @Query(value = "SELECT role FROM board_members WHERE board_id = :boardId AND user_id = :userId",
            nativeQuery = true)
    Optional<String> findMemberRole(@Param("boardId") Integer boardId, @Param("userId") Integer userId);

    /** Every member's role on one board, for {@link BoardMapper} to render the whole list at once. */
    @Query(value = "SELECT user_id, role FROM board_members WHERE board_id = :boardId", nativeQuery = true)
    List<Object[]> findMemberRoles(@Param("boardId") Integer boardId);

    /**
     * Sets a member's role - the only write this column ever needs, since a role changes at
     * acceptance (from the invitation) or when an owner edits the member list, never through the
     * {@code @ManyToMany} mapping that manages {@code board_id}/{@code user_id}. Callers flush the
     * membership insert first ({@code saveAndFlush}), or this runs before that row exists and
     * updates nothing.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE board_members SET role = :role WHERE board_id = :boardId AND user_id = :userId",
            nativeQuery = true)
    int updateMemberRole(@Param("boardId") Integer boardId, @Param("userId") Integer userId,
                          @Param("role") String role);
}
