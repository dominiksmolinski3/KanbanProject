package pl.myproject.kanbanproject2.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.service.EmailDeliveryException;
import pl.myproject.kanbanproject2.service.EmailSender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The worker on the other side of the outbox: it reads due rows and posts them, so the HTTPS call,
 * retry and give-up decision happen here instead of on the request thread. A {@code FAILED} row is
 * the dead letter, watched by {@link MailHealthIndicator} and by the {@link #DEAD_LETTER_MARKER}
 * log line a Log Analytics rule matches.
 *
 * <p>Sending is not transactional — wrapping the loop would hold a connection across up to fifty
 * HTTPS calls and roll back forty-nine successful sends over one refusal. Claiming is transactional
 * and separate: {@link OutboxClaimer} claims with {@code FOR UPDATE SKIP LOCKED} and commits before
 * any send, which is what lets a second relay run without resending.
 *
 * <p>Delivery is at-least-once: a relay that dies mid-batch leaves rows whose lease lapses and gets
 * reclaimed, so a message may be posted twice - the better failure than a verification code nobody
 * receives.
 */
@Slf4j
@Component
public class OutboxRelay {

    /**
     * How long a refused row waits before the next attempt, doubling each time: one minute, then
     * two, four, eight. Five attempts spread over about a quarter of an hour, which is the life of
     * a verification code - past that the row is a record of a failure rather than a delivery still
     * being attempted.
     */
    static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);

    /**
     * Bounded because a relay with ten thousand queued rows should send fifty and come back rather
     * than hold a thread for an hour; the next pass is a minute away. Also bounds the reclaim pass,
     * for the same reason.
     */
    static final int BATCH_SIZE = 50;

    /**
     * How long a claimed row stays claimed before another relay may assume the claimer is gone. Not
     * a timeout on one send - the whole batch is claimed at once and the last row is posted last, so
     * the lease must outlast a full pass. Ten minutes trades a slower recovery for fewer duplicate
     * sends, since a stalled relay is the recoverable failure.
     */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

    /**
     * The token the give-up line carries so something outside this process can find it. The Log
     * Analytics rule in {@code terraform/modules/diagnostics/main.tf} matches this string in
     * console logs and mails the alert address. A marker rather than a phrase from the sentence,
     * because reworded prose is an alert that silently stops firing - {@code DeadLetterAlertTest}
     * keeps the two in sync.
     */
    static final String DEAD_LETTER_MARKER = "MAIL_DEAD_LETTER";

    private final OutboxEmailRepository outbox;
    private final OutboxClaimer claimer;
    private final EmailSender transport;
    private final Clock clock;

    @Autowired
    public OutboxRelay(OutboxEmailRepository outbox,
                       OutboxClaimer claimer,
                       @Qualifier("mailTransport") EmailSender transport) {
        this(outbox, claimer, transport, Clock.systemUTC());
    }

    OutboxRelay(OutboxEmailRepository outbox, OutboxClaimer claimer, EmailSender transport, Clock clock) {
        this.outbox = outbox;
        this.claimer = claimer;
        this.transport = transport;
        this.clock = clock;
    }

    /**
     * A minute, which is the resolution the backoff is written in and about as often as is worth
     * waking up for a table that is usually empty. It runs alongside the deadline sweep in {@code
     * TaskService}, on the same scheduler {@code @EnableScheduling} provides.
     */
    @Scheduled(fixedRate = 60000)
    public void deliverPending() {
        Instant now = clock.instant();
        reclaimLapsedClaims(now);

        List<OutboxEmail> due = claimer.claimDue(now, CLAIM_LEASE, BATCH_SIZE);
        if (due.isEmpty()) {
            return;
        }
        if (!transport.deliversMessages()) {
            // Not an error and not silent: the startup warning already named the missing
            // properties, and marking these SENT would put a lie in the one table whose job is to
            // be truthful about mail.
            log.warn("Mail is not configured; dropping {} queued message(s) rather than sending them", due.size());
            due.forEach(row -> row.dropped(now));
            outbox.saveAll(due);
            return;
        }
        for (OutboxEmail row : due) {
            deliver(row, now);
        }
        // Whether any of these actually arrived is not known here and is not knowable here: the
        // provider answers that later, out of band, and MailDeliveryReportService is what writes
        // the answer back onto these same rows.
    }

    /**
     * Puts back anything a previous relay claimed and never answered for. Runs before this pass
     * claims anything, so a recovered row is due next pass rather than reposted immediately after
     * the process that may have already sent it died. Logged at warn because a lapsed claim means a
     * relay was killed holding a batch; a row that has run out of attempts here is a dead letter
     * like any other and carries the same marker.
     */
    private void reclaimLapsedClaims(Instant now) {
        List<OutboxEmail> lapsed = claimer.reclaimLapsed(now, BATCH_SIZE);
        if (lapsed.isEmpty()) {
            return;
        }
        log.warn("Reclaimed {} outbox row(s) whose claim lapsed; a relay stopped before answering for them",
                lapsed.size());
        for (OutboxEmail row : lapsed) {
            if (row.getStatus() == OutboxStatus.FAILED) {
                log.error("{}: outbox message {} was claimed and abandoned {} times; giving up",
                        DEAD_LETTER_MARKER, row.getId(), row.getAttempts());
            }
        }
    }

    private void deliver(OutboxEmail row, Instant now) {
        try {
            // The provider's id for the message, which a later delivery report matches against;
            // null is ordinary and means this row can never be matched, not that the send failed.
            row.accepted(now, transport.send(row.asMessage()));
        } catch (EmailDeliveryException refusal) {
            row.refused(reasonOf(refusal), now, FIRST_BACKOFF);
            if (row.getStatus() == OutboxStatus.FAILED) {
                log.error("{}: outbox message {} refused {} times; giving up",
                        DEAD_LETTER_MARKER, row.getId(), row.getAttempts(), refusal);
            } else {
                log.warn("Outbox message {} refused on attempt {}; retrying at {}",
                        row.getId(), row.getAttempts(), row.getNextAttemptAt());
            }
        }
        outbox.save(row);
    }

    /**
     * The provider's complaint, not this application's wrapper around it - "would not accept the
     * message" says nothing a reader of the table does not already know from the status.
     */
    private static String reasonOf(EmailDeliveryException refusal) {
        return refusal.getCause() == null ? refusal.getMessage() : String.valueOf(refusal.getCause().getMessage());
    }
}
