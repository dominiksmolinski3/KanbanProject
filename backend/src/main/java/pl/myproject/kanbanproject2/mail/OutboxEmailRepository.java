package pl.myproject.kanbanproject2.mail;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

    /**
     * The row one delivery report is about, found by the id Azure gave the message.
     *
     * <p>The webhook's only query, and the only reason {@code provider_message_id} is written at
     * all. {@code V16} indexes that column uniquely where it is not null, so this cannot quietly
     * pick one of two rows claiming the same send.
     */
    Optional<OutboxEmail> findByProviderMessageId(String providerMessageId);

    /**
     * How many messages this deployment believes it sent and has since been told did not arrive.
     *
     * <p>A count rather than the rows, for the reason every other count here is a count: these
     * bodies carry live verification codes and a health endpoint has no business loading one. It
     * is windowed by report time rather than by creation, because the question it answers is
     * whether mail is arriving <em>now</em> - a bounce from March says nothing about this morning.
     */
    long countByDeliveryStatusInAndDeliveryReportedAtGreaterThanEqual(
            Collection<String> statuses, Instant since);

    /**
     * How many recently-sent messages have been told what became of them, and how many are still
     * waiting to be.
     *
     * <p>These two exist because <em>arriving safely leaves no trace</em>. A report that matches its
     * row is recorded and logged at {@code debug}; a report that matches nothing is dropped and
     * logged at {@code debug}. At the level production runs, both are silence, and
     * {@code undelivered} counts only failures - so it reads {@code 0} both when every message
     * arrives and when no report has ever found the row it names.
     *
     * <p>The failure that hides is a real one and the codebase already names it:
     * {@code AcsEmailSender} reads the provider's message id <em>best-effort</em>, so an id it
     * cannot read is {@code null} and a row that can never be matched. If that read stops working -
     * an SDK bump, a changed response shape - every later report misses, quietly, and the
     * deployment looks exactly like one where nothing has ever bounced. That is the shape MAIL-04,
     * issue #96 and the unwatched sweeps each had: <strong>a signal whose absence is
     * indistinguishable from the normal state.</strong>
     *
     * <p>Two numbers beside each other answer it. {@code reported} rising with {@code sent} is the
     * join working; {@code sent} climbing while {@code reported} stays at zero is the join broken,
     * and nothing else in this deployment would say so.
     */
    long countByStatusAndDeliveryStatusIsNotNullAndCreatedAtGreaterThanEqual(
            OutboxStatus status, Instant since);

    /** @see #countByStatusAndDeliveryStatusIsNotNullAndCreatedAtGreaterThanEqual */
    long countByStatusAndCreatedAtGreaterThanEqualAndProviderMessageIdIsNull(
            OutboxStatus status, Instant since);
}
