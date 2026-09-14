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
 * The worker on the other side of the outbox: it reads rows and posts them.
 *
 * <p>Everything the request thread used to wait for happens here instead - the HTTPS call, the
 * timeout, the retry, and the decision that a message is not going to be delivered. That is the
 * whole trade of the pattern, and it has a cost worth naming: a refused message used to be a 500
 * somebody saw, and is now a row. A {@code FAILED} row is the dead letter, and a dead-letter queue
 * nobody watches is a silently dropped mail with extra steps - so two things watch it now.
 * {@link MailHealthIndicator} answers for anyone who asks, and the {@link #DEAD_LETTER_MARKER} on
 * the give-up line below is what the Log Analytics rule matches for the far more common case of
 * nobody asking.
 *
 * <p><b>The sending is not transactional, deliberately.</b> The batch is claimed, then each row is
 * posted and saved on its own. Wrapping the loop in a transaction would hold one open across up to
 * fifty HTTPS round trips - a connection held for minutes against a pool sized for requests - and
 * would roll back the record of forty-nine sent messages because the fiftieth was refused. Every
 * one of those messages really was sent; the row saying so has to survive the one that was not.
 *
 * <p><b>The claiming is, and it is a different transaction.</b> {@link OutboxClaimer} selects
 * {@code FOR UPDATE SKIP LOCKED} and marks the rows {@code SENDING} in one short transaction that
 * commits before any message is posted, which is what lets a second relay run at all: it steps over
 * the rows this one is holding instead of sending them again. Without a claim two replicas post
 * every verification code, reset code, overdue notice and invitation twice - externally, to a
 * person, with nothing able to recall it - which is why this was the first of the single-replica
 * constraints to be lifted and not the last.
 *
 * <p><b>At least once, and that is the choice.</b> A relay that dies between the claim and the
 * answer leaves rows in {@code SENDING}; their lease lapses and a later pass puts them back. A row
 * that was already posted when the process died is then posted again. The other ordering - mark
 * sent, then send - trades that duplicate for a message nobody ever receives, and for a mailbox
 * holding a verification code, arriving twice is the better failure.
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
     * How many rows one pass takes.
     *
     * <p>Bounded because a relay that wakes to ten thousand rows should send fifty and come back
     * rather than hold one thread for an hour; the next pass is a minute away. It bounds the
     * reclaim pass too, for the same reason and with the same effect - a backlog is worked through
     * a batch at a time rather than in one long transaction.
     */
    static final int BATCH_SIZE = 50;

    /**
     * How long a claimed row stays claimed before another relay may assume the claimer is gone.
     *
     * <p><b>Not a timeout on one send.</b> Every row in a batch is claimed at the same instant and
     * the fiftieth is posted last, so the lease has to outlast a whole pass - ten minutes is twelve
     * seconds a message, against a provider that answers in well under one. Too short and a slow
     * afternoon becomes duplicate mail; too long and a relay killed mid-batch leaves its messages
     * unsent for that long. Ten minutes errs toward the second, which is the recoverable one.
     */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

    /**
     * The token the give-up line carries so that something outside this process can find it.
     *
     * <p>{@code MailHealthIndicator} answers "is mail working" to anyone who asks; this is the
     * other half, for the far more common case of nobody asking. The Log Analytics rule in {@code
     * terraform/modules/diagnostics/main.tf} matches console log lines containing this string and
     * mails whoever the alert address names, which is where the 5xx and restart alerts already go.
     *
     * <p>A marker rather than a phrase from the sentence because the sentence is prose and prose
     * gets reworded, and a reworded log line is an alert that stops firing without anything
     * failing. {@code DeadLetterAlertTest} reads the Terraform and fails the build if the two stop
     * agreeing - the coupling is real and nothing else can see it.
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
     * Puts back anything a previous relay claimed and never answered for.
     *
     * <p>It runs first, before this pass claims anything, so a row recovered here is due again for
     * the pass a minute from now rather than being re-posted a millisecond after the process that
     * may already have posted it died. That gap is free and it is the cheapest form of the
     * duplicate-suppression this design does not otherwise have.
     *
     * <p><b>Loud, because it should not happen.</b> A lapsed claim means a relay was killed holding
     * a batch - a rollout at the wrong moment, an OOM, a node drained - and that is worth knowing
     * even though the outcome is recovery rather than loss. A row that has now run out of attempts
     * is a dead letter like any other and carries the same marker, so the alert that watches
     * refusals watches this too.
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
            // What comes back is the provider's own id for the message, which is the only thing a
            // delivery report arriving later has to match against. Null is ordinary and is stored
            // as null: it means this row can never be matched to a report, not that the send
            // failed.
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
