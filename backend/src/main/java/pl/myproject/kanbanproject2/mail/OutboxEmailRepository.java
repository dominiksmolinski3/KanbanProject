package pl.myproject.kanbanproject2.mail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OutboxEmailRepository extends JpaRepository<OutboxEmail, Long> {

    @Query(value = """
            SELECT * FROM email_outbox
            WHERE status = :status
              AND next_attempt_at <= :now
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEmail> claimBatch(@Param("status") String status,
                                 @Param("now") Instant now,
                                 @Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    long countByStatusAndCreatedAtGreaterThanEqual(OutboxStatus status, Instant since);

    Optional<OutboxEmail> findByProviderMessageId(String providerMessageId);

    long countByDeliveryStatusInAndDeliveryReportedAtGreaterThanEqual(
            Collection<String> statuses, Instant since);

    long countByStatusAndDeliveryStatusIsNotNullAndCreatedAtGreaterThanEqual(
            OutboxStatus status, Instant since);

    long countByStatusAndCreatedAtGreaterThanEqualAndProviderMessageIdIsNull(
            OutboxStatus status, Instant since);
}
