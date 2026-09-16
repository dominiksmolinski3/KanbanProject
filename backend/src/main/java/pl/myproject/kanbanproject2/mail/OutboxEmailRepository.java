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

    /**
     * The batch one relay pass posts, oldest first, locked so no other relay takes the same rows.
     * Bounded to fifty for the same reason as {@link OutboxRelay#BATCH_SIZE}; oldest first because a
     * backlog worked newest-first would deliver already-expired verification codes first.
     *
     * <p><b>{@code FOR UPDATE SKIP LOCKED} is what makes a second replica safe</b>: without it two
     * relays select the same rows and every message goes out twice, externally, with no way to
     * recall it. A plain {@code FOR UPDATE} would be correct and much worse - the second relay would
     * block behind the first instead of taking the next batch. The lock itself only lasts the short
     * claim transaction; {@link OutboxEmail#claimed} writes {@code SENDING} inside it, which is what
     * keeps the row invisible to this query's {@code PENDING} filter afterward.
     *
     * <p><b>Native, deliberately</b>: {@code @Lock(PESSIMISTIC_WRITE)} with a lock-timeout hint is
     * the portable spelling for {@code SKIP LOCKED} but fails silently when a provider doesn't honor
     * the hint, leaving an indistinguishable blocking {@code FOR UPDATE}. The cost is that a native
     * query is invisible to {@code QueryStringsResolveTest} (which only compiles the HQL ones), so
     * nothing but a database checks this string; {@code OutboxClaimQueryTest} pins the clause
     * instead.
     *
     * <p><b>One query, two callers</b>: {@link OutboxClaimer} asks it for due {@code PENDING} rows
     * and for {@code SENDING} rows whose lease lapsed - the same question against two statuses,
     * since {@code next_attempt_at} means "when the relay may next take this row" in both.
     */
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

    /**
     * How many rows are in one state, for {@link MailHealthIndicator}. Counted rather than fetched:
     * these rows carry live verification codes and a health endpoint has no business loading one.
     */
    long countByStatus(OutboxStatus status);

    /**
     * The same count, narrowed to rows written since some instant, so the health status can clear -
     * a message the relay gave up on last spring shouldn't hold today's status red.
     */
    long countByStatusAndCreatedAtGreaterThanEqual(OutboxStatus status, Instant since);

    /**
     * The row one delivery report is about, found by the id Azure gave the message. {@code V16}
     * indexes {@code provider_message_id} uniquely where not null, so this can't quietly pick one of
     * two rows claiming the same send.
     */
    Optional<OutboxEmail> findByProviderMessageId(String providerMessageId);

    /**
     * How many messages this deployment believes it sent and has since been told did not arrive.
     * Windowed by report time rather than creation, since the question is whether mail is arriving
     * <em>now</em> - a bounce from March says nothing about this morning.
     */
    long countByDeliveryStatusInAndDeliveryReportedAtGreaterThanEqual(
            Collection<String> statuses, Instant since);

    /**
     * How many recently-sent messages have been told what became of them, and how many are still
     * waiting. These exist because arriving safely leaves no trace: both a matched and unmatched
     * report log at {@code debug}, so {@code undelivered} alone reads {@code 0} whether every
     * message arrived or no report ever found its row - the latter being a real risk, since
     * {@code AcsEmailSender} reads the provider's message id best-effort and a silent break there
     * would hide every later report (the same "absent signal reads as normal" shape as MAIL-04).
     * {@code reported} rising with {@code sent} is the join working; flat at zero while {@code sent}
     * climbs is the join broken.
     */
    long countByStatusAndDeliveryStatusIsNotNullAndCreatedAtGreaterThanEqual(
            OutboxStatus status, Instant since);

    /** @see #countByStatusAndDeliveryStatusIsNotNullAndCreatedAtGreaterThanEqual */
    long countByStatusAndCreatedAtGreaterThanEqualAndProviderMessageIdIsNull(
            OutboxStatus status, Instant since);
}
