package pl.myproject.kanbanproject2.mail;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.service.EmailSender;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component("mail")
public class MailHealthIndicator implements HealthIndicator {

    static final Duration RECENT = Duration.ofHours(24);

    static final String PENDING_GAUGE = "kanban.mail.outbox.pending";

    private final OutboxEmailRepository outbox;
    private final EmailSender transport;
    private final Clock clock;

    @Autowired
    public MailHealthIndicator(OutboxEmailRepository outbox,
                               @Qualifier("mailTransport") EmailSender transport,
                               MeterRegistry registry) {
        this(outbox, transport, Clock.systemUTC(), registry);
    }

    MailHealthIndicator(OutboxEmailRepository outbox, EmailSender transport, Clock clock) {
        this(outbox, transport, clock, new SimpleMeterRegistry());
    }

    MailHealthIndicator(OutboxEmailRepository outbox, EmailSender transport, Clock clock, MeterRegistry registry) {
        this.outbox = outbox;
        this.transport = transport;
        this.clock = clock;
        Gauge.builder(PENDING_GAUGE, outbox, repo -> repo.countByStatus(OutboxStatus.PENDING))
                .description("Outbox email rows currently pending delivery")
                .register(registry);
    }

    @Override
    public Health health() {
        Instant since = clock.instant().minus(RECENT);
        long failed = outbox.countByStatus(OutboxStatus.FAILED);
        long failedRecently = outbox.countByStatusAndCreatedAtGreaterThanEqual(OutboxStatus.FAILED, since);
        long undelivered = outbox.countByDeliveryStatusInAndDeliveryReportedAtGreaterThanEqual(
                MailDeliveryStatuses.UNDELIVERED, since);
        long sent = outbox.countByStatusAndCreatedAtGreaterThanEqual(OutboxStatus.SENT, since);
        long reported = outbox.countByStatusAndDeliveryStatusIsNotNullAndCreatedAtGreaterThanEqual(
                OutboxStatus.SENT, since);
        long sentWithoutProviderId =
                outbox.countByStatusAndCreatedAtGreaterThanEqualAndProviderMessageIdIsNull(
                        OutboxStatus.SENT, since);

        if (!transport.deliversMessages()) {
            return Health.outOfService()
                    .withDetail("reason", "no mail account is configured; queued messages are dropped")
                    .withDetail("dropped", outbox.countByStatus(OutboxStatus.DROPPED))
                    .withDetail("pending", outbox.countByStatus(OutboxStatus.PENDING))
                    .withDetail("sending", outbox.countByStatus(OutboxStatus.SENDING))
                    .withDetail("failed", failed)
                    .build();
        }
        if (failedRecently > 0) {
            return Health.down()
                    .withDetail("reason", "the relay gave up on mail the provider would not accept")
                    .withDetail("failedRecently", failedRecently)
                    .withDetail("failed", failed)
                    .withDetail("pending", outbox.countByStatus(OutboxStatus.PENDING))
                    .withDetail("sending", outbox.countByStatus(OutboxStatus.SENDING))
                    .withDetail("undelivered", undelivered)
                    .withDetail("sent", sent)
                    .withDetail("reported", reported)
                    .withDetail("sentAwaitingReport", sent - reported)
                    .withDetail("sentWithoutProviderId", sentWithoutProviderId)
                    .build();
        }
        return Health.up()
                .withDetail("pending", outbox.countByStatus(OutboxStatus.PENDING))
                .withDetail("sending", outbox.countByStatus(OutboxStatus.SENDING))
                .withDetail("failed", failed)
                .withDetail("undelivered", undelivered)
                .withDetail("sent", sent)
                .withDetail("reported", reported)
                .withDetail("sentAwaitingReport", sent - reported)
                .withDetail("sentWithoutProviderId", sentWithoutProviderId)
                .build();
    }
}
