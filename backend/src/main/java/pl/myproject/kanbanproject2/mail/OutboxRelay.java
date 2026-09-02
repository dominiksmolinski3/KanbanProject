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
 * <p><b>Not transactional, deliberately.</b> The batch is read, then each row is posted and saved
 * on its own. Wrapping the loop in a transaction would hold one open across up to fifty HTTPS
 * round trips - a connection held for minutes against a pool sized for requests - and would roll
 * back the record of forty-nine sent messages because the fiftieth was refused. Every one of those
 * messages really was sent; the row saying so has to survive the one that was not.
 *
 * <p><b>One replica.</b> Nothing claims a row, so two relays would post every message twice. The
 * deployment pins replicas to 1 already, for the in-memory broker and the in-memory rate limiter,
 * and this joins that list rather than adding to it - see {@link OutboxEmailRepository} for the
 * change a second replica needs.
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
    private final EmailSender transport;
    private final Clock clock;

    @Autowired
    public OutboxRelay(OutboxEmailRepository outbox, @Qualifier("mailTransport") EmailSender transport) {
        this(outbox, transport, Clock.systemUTC());
    }

    OutboxRelay(OutboxEmailRepository outbox, EmailSender transport, Clock clock) {
        this.outbox = outbox;
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
        List<OutboxEmail> due =
                outbox.findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(OutboxStatus.PENDING, now);
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
    }

    private void deliver(OutboxEmail row, Instant now) {
        try {
            transport.send(row.asMessage());
            row.accepted(now);
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
