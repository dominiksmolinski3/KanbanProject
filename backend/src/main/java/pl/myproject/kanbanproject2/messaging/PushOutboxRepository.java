package pl.myproject.kanbanproject2.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PushOutboxRepository extends JpaRepository<PushOutbox, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO push_outbox (endpoint_id, conversation_id, seq, attempts, next_attempt_at, created_at)
            VALUES (:endpointId, :conversationId, :seq, 0, :now, :now)
            ON CONFLICT (endpoint_id, conversation_id)
            DO UPDATE SET seq = EXCLUDED.seq, attempts = 0, next_attempt_at = EXCLUDED.next_attempt_at
            """, nativeQuery = true)
    int enqueue(@Param("endpointId") Long endpointId, @Param("conversationId") Long conversationId,
                @Param("seq") Long seq, @Param("now") Instant now);

    @Query(value = """
            SELECT * FROM push_outbox
            WHERE next_attempt_at <= :now
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PushOutbox> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query("DELETE FROM PushOutbox o WHERE o.id = :id AND o.seq = :seq")
    int deleteDelivered(@Param("id") Long id, @Param("seq") Long seq);
}
