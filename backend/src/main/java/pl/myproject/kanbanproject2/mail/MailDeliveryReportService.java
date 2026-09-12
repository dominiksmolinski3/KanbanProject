package pl.myproject.kanbanproject2.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Writes what the provider says became of a message onto the row that message came from.
 *
 * <p>The outbox has been able to say "Azure took it" since {@code V10} and nothing more. That is
 * acceptance, and acceptance is not delivery: a hard bounce, a spam rejection or an address that
 * does not exist all happen afterwards and out of band. Azure publishes a report per recipient;
 * this is what receives one and attaches it to the message it is about.
 *
 * <p><b>Nothing here throws.</b> Same rule as {@code TaskActivityRecorder}, for the same reason
 * one step further out: this runs on somebody else's request - Event Grid's - and Event Grid reads
 * a non-2xx as "try again", with its own retry schedule and a dead-letter of its own. A report
 * about a message this deployment has never heard of is not an error; it is the ordinary state of
 * affairs for a row queued before {@code V16}, for a row whose id could not be read back, and for
 * a subscription pointed at the wrong environment. Answering "please retry" to any of those buys a
 * report delivered a hundred times and recorded zero.
 *
 * <p><b>It does not create rows either.</b> A report names a message, and a message this
 * application did not send is not this application's business. The alternative - inserting a row
 * for an unknown id - would let anyone who can reach the webhook write into the one table that
 * holds live verification codes.
 */
@Slf4j
@Service
public class MailDeliveryReportService {

    private final OutboxEmailRepository outbox;
    private final Clock clock;

    @Autowired
    public MailDeliveryReportService(OutboxEmailRepository outbox) {
        this(outbox, Clock.systemUTC());
    }

    MailDeliveryReportService(OutboxEmailRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Records one report, if it names a message this application sent.
     *
     * @return whether a row was found and updated, which is what the tests assert and what nothing
     *     in production branches on - the webhook answers the same either way.
     */
    @Transactional
    public boolean record(EventGridNotification.Data report) {
        if (report == null || report.messageId() == null || report.messageId().isBlank()) {
            log.warn("A delivery report arrived naming no message; ignoring it");
            return false;
        }
        Optional<OutboxEmail> row = outbox.findByProviderMessageId(report.messageId());
        if (row.isEmpty()) {
            // Expected, routinely: see the class note. Debug rather than warn, or a subscription
            // shared with another environment fills the log with lines nobody can act on.
            log.debug("A delivery report named message {}, which is not in this outbox", report.messageId());
            return false;
        }

        OutboxEmail message = row.get();
        Instant reportedAt = report.deliveryAttemptTimestamp() == null
                // A report with no timestamp is one we can only order by arrival, so arrival is
                // what it gets. Later reports still win; this one simply cannot lose to one that
                // arrives after it.
                ? clock.instant()
                : report.deliveryAttemptTimestamp();

        boolean kept = message.deliveryReported(report.status(), detailOf(report), reportedAt);
        if (!kept) {
            log.debug("Ignored a delivery report for outbox message {}: it is older than the one on the row",
                    message.getId());
            return false;
        }
        outbox.save(message);

        if (MailDeliveryStatuses.isUndelivered(report.status())) {
            // Logged at warn because it is the thing somebody would want to know and the first
            // place they will look. The alert that mails them is the Log Analytics rule over the
            // provider's own table - this is not a second alert, it is the local record.
            log.warn("Outbox message {} was accepted and then not delivered: {}",
                    message.getId(), report.status());
        } else {
            log.debug("Outbox message {} reported as {}", message.getId(), report.status());
        }
        return true;
    }

    /**
     * The provider's explanation, when it gives one. Only this field: the rest of the report is
     * either already on the row or is the recipient's address, which the row also already has.
     */
    private static String detailOf(EventGridNotification.Data report) {
        return report.deliveryStatusDetails() == null ? null : report.deliveryStatusDetails().statusMessage();
    }
}
