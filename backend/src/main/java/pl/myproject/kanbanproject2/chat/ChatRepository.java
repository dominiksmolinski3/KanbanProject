package pl.myproject.kanbanproject2.chat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.myproject.kanbanproject2.board.Board;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Until {@code V18} this interface had an empty body, so every message written here was written to
 * be unreadable - the same charge {@code CLAUDE.md} levels at the {@code files} table when it calls
 * it the counter-example rather than the model.
 */
@Repository
public interface ChatRepository extends JpaRepository<Chat, Integer> {

    /** Newest first, with id in the sort because timestamp ties and a tied order repeats rows. */
    Page<Chat> findByBoardOrderByTimestampDescIdDesc(Board board, Pageable pageable);

    /**
     * The thread between two accounts, in both directions - a message is theirs whichever of them
     * sent it. {@code board is null} is what keeps a board message out of a direct thread; the two
     * are mutually exclusive on every row this application writes.
     */
    @Query("""
            select c from Chat c
            where c.board is null
              and ((c.sender = :one and c.recipientId = :other)
                or (c.sender = :other and c.recipientId = :one))
            order by c.timestamp desc, c.id desc
            """)
    Page<Chat> findDirectThread(@Param("one") String one, @Param("other") String other, Pageable pageable);

    /** For {@code BoardService.deleteBoard}: nothing cascades here, deliberately. */
    List<Chat> findByBoard(Board board);

    @Modifying
    @Query("delete from Chat c where c.timestamp < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
