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

/**
 * The thing that notices when mail stops working. A refused message used to answer
 * {@code 500 EMAIL_SEND_FAILED} on a signup (which the 5xx alert already covers) and is now a
 * {@code FAILED} row nothing reads; a deployment with no mail account has always started normally
 * and dropped every message. Both trades are right but came with no replacement signal - this is
 * that signal, reported on {@code /actuator/health} under the key {@code mail}:
 *
 * <ul>
 *   <li>{@code OUT_OF_SERVICE} when no mail account is configured - nothing is broken, the
 *       transport is doing what it was asked. {@code dropped} shows the cost.</li>
 *   <li>{@code DOWN} when the relay has given up on a message recently (a day, not ever - so the
 *       status can clear); {@code failed} carries the all-time total regardless.</li>
 *   <li>{@code UP} otherwise, with the queue depth as a detail.</li>
 * </ul>
 *
 * <p><b>{@code undelivered} is a detail, never a status.</b> A bounce is a fact about one mailbox,
 * not a fault in this deployment, and a status that went red for it would be red most weeks and
 * read by nobody - the Log Analytics rule over the provider's own table is what actually alarms on
 * bounces.
 *
 * <p><b>{@code reported} and {@code sentAwaitingReport} exist because arriving safely leaves no
 * trace</b> - both a matched and an unmatched report log at {@code debug}, so at production's log
 * level both are silent, and {@code undelivered} reads {@code 0} whether every message arrived or
 * no report ever found its row. That second case is real: {@code AcsEmailSender} reads the
 * provider's message id best-effort, and a read that silently breaks would leave every later report
 * missing with {@code undelivered: 0} and no alarm - the same "absent signal reads as normal" shape
 * MAIL-04 and the unwatched CD sweep had. {@code reported} climbing with {@code sent} is the join
 * working; {@code sent} climbing while {@code reported} stays at zero is the join broken.
 * {@code sentWithoutProviderId} is the sharper measure of the same failure, directly rather than by
 * consequence. Both are details, not statuses, for the same reason as {@code undelivered}: a
 * report arrives minutes after send, so messages awaiting one are the normal state after any
 * signup.
 *
 * <p><b>This cannot take the deployment down, checked rather than assumed.</b> The container's
 * probes address the {@code readiness}/{@code liveness} health <em>groups</em>, which hold only
 * Spring's own states; a plain indicator like this one joins the root endpoint and no group, so
 * mail being off is visible but never restarts the container.
 *
 * <p><b>{@code sending}</b> exists because a claimed row is no longer counted as {@code pending}, so
 * without it a queue being worked looks empty - and a relay killed mid-batch would be invisible
 * until its lease lapsed.
 *
 * <p>Counts are details, hidden from anonymous callers by
 * {@code management.endpoint.health.show-details=when_authorized}; the public endpoint says
 * {@code UP} or it does not.
 */
@Component("mail")
public class MailHealthIndicator implements HealthIndicator {

    /**
     * How far back a give-up still counts against the status. A day, so a morning arrival still
     * sees last night's failure but an old one doesn't stand in front of today's; the total is
     * reported regardless, so nothing is hidden by the window - only un-alarmed.
     */
    static final Duration RECENT = Duration.ofHours(24);

    /**
     * The gauge name for the pending-row count - the trend line the {@code pending} health detail
     * never had, since a health check is one instant and {@code /actuator/metrics} keeps a series.
     * Reads through the same {@link OutboxEmailRepository#countByStatus} query {@link #health()}
     * already runs, rather than a second one, and is held by a weak reference on this bean per
     * {@link Gauge.Builder#register}, which is safe here because the indicator is a Spring
     * singleton that outlives the registry.
     */
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
