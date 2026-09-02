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

    /**
     * How many rows are in one state, for {@link MailHealthIndicator}.
     *
     * <p>Counted rather than fetched: the caller wants a number, and these rows carry live
     * verification codes in their bodies. A health endpoint has no business loading one.
     */
    long countByStatus(OutboxStatus status);

    /**
     * The same count, narrowed to rows written since some instant.
     *
     * <p>It exists so the health status can clear. A message the relay gave up on last spring is
     * worth keeping in the total and is not worth holding a status red over, and an alarm that
     * cannot go quiet stops being read. Keyed on {@code created_at} rather than on the last
     * attempt because that is the column the row is ordered by everywhere else, and for a row that
     * has run out of attempts the two are about a quarter of an hour apart.
     */
    long countByStatusAndCreatedAtGreaterThanEqual(OutboxStatus status, Instant since);
}
