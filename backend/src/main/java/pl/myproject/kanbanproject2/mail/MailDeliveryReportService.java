package pl.myproject.kanbanproject2.mail;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
public class MailDeliveryReportService {

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

    @Transactional
    public boolean record(EventGridNotification.Data report) {
        if (report == null || report.messageId() == null || report.messageId().isBlank()) {
            log.warn("A delivery report arrived naming no message; ignoring it");
            return false;
        }
        Optional<OutboxEmail> row = outbox.findByProviderMessageId(report.messageId());
        if (row.isEmpty()) {
            log.debug("A delivery report named message {}, which is not in this outbox", report.messageId());
            return false;
        }

        OutboxEmail message = row.get();
        Instant reportedAt = report.deliveryAttemptTimestamp() == null
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
            meterRegistry.counter(UNDELIVERED_COUNTER, "status", MailDeliveryStatuses.canonical(report.status()))
                    .increment();
            log.warn("Outbox message {} was accepted and then not delivered: {}",
                    message.getId(), report.status());
        } else {
            log.debug("Outbox message {} reported as {}", message.getId(), report.status());
        }
        return true;
    }

    private static String detailOf(EventGridNotification.Data report) {
        return report.deliveryStatusDetails() == null ? null : report.deliveryStatusDetails().statusMessage();
    }
}
