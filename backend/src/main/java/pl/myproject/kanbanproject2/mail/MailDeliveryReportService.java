package pl.myproject.kanbanproject2.mail;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Writes what the provider says became of a message onto the row it came from. The outbox has only
 * been able to say "Azure took it" since {@code V10}; acceptance is not delivery, and a bounce or
 * spam rejection happens afterwards and out of band via a per-recipient report from Azure.
 *
 * <p><b>Nothing here throws</b>, same rule as {@code TaskActivityRecorder} one step further out:
 * this runs on Event Grid's request, which reads a non-2xx as "try again". A report naming a
 * message this deployment never heard of is ordinary (a row queued before {@code V16}, an unreadable
 * id, a misdirected subscription), not an error.
 *
 * <p><b>It does not create rows either</b> - a report names a message, and inserting a row for an
 * unknown id would let anyone reaching the webhook write into the table holding live verification
 * codes.
 */
@Slf4j
@Service
public class MailDeliveryReportService {

    /**
     * Counts reports that say a message did not arrive, tagged with which of
     * {@link MailDeliveryStatuses#UNDELIVERED} it was. The bounce alert reads this, so which statuses
     * count as "did not arrive" is decided here and nowhere else; the alert used to repeat the list
     * in KQL, with a test to keep the two copies agreeing.
     */
    static final String UNDELIVERED_COUNTER = "kanban.mail.delivery.undelivered";

    private final OutboxEmailRepository outbox;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public MailDeliveryReportService(OutboxEmailRepository outbox, MeterRegistry meterRegistry) {
        this(outbox, meterRegistry, Clock.systemUTC());
    }

    MailDeliveryReportService(OutboxEmailRepository outbox, MeterRegistry meterRegistry, Clock clock) {
        this.outbox = outbox;
        this.meterRegistry = meterRegistry;
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
            // Expected routinely (see class note); debug rather than warn, or a subscription
            // shared with another environment fills the log with lines nobody can act on.
            log.debug("A delivery report named message {}, which is not in this outbox", report.messageId());
            return false;
        }

        OutboxEmail message = row.get();
        Instant reportedAt = report.deliveryAttemptTimestamp() == null
                // No timestamp means we can only order by arrival; later reports still win.
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
            // Counted only once the report is kept, so a stale report that loses to a newer one
            // on the row cannot count a bounce the row does not show. Tagged with the canonical
            // spelling rather than the provider's, which keeps one series per status.
            meterRegistry.counter(UNDELIVERED_COUNTER, "status", MailDeliveryStatuses.canonical(report.status()))
                    .increment();
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
