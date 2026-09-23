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

@Repository
public interface ChatRepository extends JpaRepository<Chat, Integer> {

    Page<Chat> findByBoardOrderByTimestampDescIdDesc(Board board, Pageable pageable);

    @Query("""
            select c from Chat c
            where c.board is null
              and ((c.sender = :one and c.recipientId = :other)
                or (c.sender = :other and c.recipientId = :one))
            order by c.timestamp desc, c.id desc
            """)
    Page<Chat> findDirectThread(@Param("one") String one, @Param("other") String other, Pageable pageable);

    List<Chat> findByBoard(Board board);

    @Modifying
    @Query("delete from Chat c where c.timestamp < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
