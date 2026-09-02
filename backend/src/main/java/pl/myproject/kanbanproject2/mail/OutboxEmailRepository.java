package pl.myproject.kanbanproject2.mail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface OutboxEmailRepository extends JpaRepository<OutboxEmail, Long> {

    /**
     * The batch one relay pass posts, oldest first.
     *
     * <p>Bounded because a relay that wakes up to ten thousand rows should send fifty of them and
     * come back rather than hold one thread for an hour; the next pass is a minute away. Oldest
     * first because a verification code has a fifteen-minute life and a backlog worked newest-first
     * delivers the ones that have already expired.
     *
     * <p>There is no locking here, and that is a real constraint rather than an omission: two
     * replicas running this would both claim the same rows and send every message twice. The
     * deployment pins replicas to 1 for the in-memory broker and the in-memory rate limiter
     * already, and a claim - {@code FOR UPDATE SKIP LOCKED}, or a status transition to {@code
     * SENDING} - is the change that has to land with the second replica, not before it.
     */
    List<OutboxEmail> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            OutboxStatus status, Instant now);
}
