package pl.myproject.kanbanproject2.mail;

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
 * The thing that notices when mail stops working.
 *
 * <p>Two failures used to be loud and are now quiet, and both were made quiet on purpose. A
 * provider that refuses a message used to answer {@code 500 EMAIL_SEND_FAILED} on a signup - which
 * is exactly what an alert on the 5xx rate is built for - and is now a {@code FAILED} row in a
 * table nothing reads. A deployment with no Communication Services resource has always started
 * normally and dropped every message, because that is what lets CI and a fresh clone run the suite
 * without an Azure account. Both trades are the right one; neither came with a replacement signal.
 *
 * <p>This is the replacement. It reports on {@code /actuator/health} under the key {@code mail}:
 *
 * <ul>
 *   <li>{@code OUT_OF_SERVICE} when no mail account is configured. Not {@code DOWN}, because
 *       nothing is broken - the transport is doing precisely what it was asked to do. The
 *       {@code dropped} count is what makes the cost of it visible: it is how many messages this
 *       deployment has thrown away.</li>
 *   <li>{@code DOWN} when the relay has given up on a message recently. Recently rather than ever,
 *       because a status that cannot clear is one people stop reading - a failure from March
 *       should not still be holding the light red in September. {@code failed} carries the total
 *       either way.</li>
 *   <li>{@code UP} otherwise, with the queue depth as a detail.</li>
 * </ul>
 *
 * <p><b>This cannot take the deployment down, and that is checked rather than assumed.</b> The
 * container's startup, readiness and liveness probes all address {@code /actuator/health/readiness}
 * and {@code /actuator/health/liveness} - the two <em>groups</em>, which contain only Spring's own
 * {@code readinessState} and {@code livenessState}. A plain indicator like this one joins the root
 * health endpoint and no group, so a mail account nobody configured makes the root endpoint say so
 * and leaves the revision serving. Mail being off is a reason to tell somebody; it is not a reason
 * to restart the container, and an indicator that conflated the two would be worse than none - the
 * failure it reports would take out the application that reports it.
 *
 * <p>Counts are details, and {@code management.endpoint.health.show-details=when_authorized} keeps
 * details away from anonymous callers. The public endpoint says {@code UP} or it does not.
 */
@Component("mail")
public class MailHealthIndicator implements HealthIndicator {

    /**
     * How far back a give-up still counts against the status.
     *
     * <p>A day, because the point of the window is that somebody arriving in the morning still
     * sees last night's failure, and that a failure nobody could act on months ago is not still
     * standing in front of the one today. The total is reported regardless, so nothing is hidden
     * by the window - only un-alarmed.
     */
    static final Duration RECENT = Duration.ofHours(24);

    private final OutboxEmailRepository outbox;
    private final EmailSender transport;
    private final Clock clock;

    @Autowired
    public MailHealthIndicator(OutboxEmailRepository outbox,
                               @Qualifier("mailTransport") EmailSender transport) {
        this(outbox, transport, Clock.systemUTC());
    }

    MailHealthIndicator(OutboxEmailRepository outbox, EmailSender transport, Clock clock) {
        this.outbox = outbox;
        this.transport = transport;
        this.clock = clock;
    }

    @Override
    public Health health() {
        Instant since = clock.instant().minus(RECENT);
        long failed = outbox.countByStatus(OutboxStatus.FAILED);
        long failedRecently = outbox.countByStatusAndCreatedAtGreaterThanEqual(OutboxStatus.FAILED, since);

        if (!transport.deliversMessages()) {
            return Health.outOfService()
                    .withDetail("reason", "no mail account is configured; queued messages are dropped")
                    .withDetail("dropped", outbox.countByStatus(OutboxStatus.DROPPED))
                    .withDetail("pending", outbox.countByStatus(OutboxStatus.PENDING))
                    .withDetail("failed", failed)
                    .build();
        }
        if (failedRecently > 0) {
            return Health.down()
                    .withDetail("reason", "the relay gave up on mail the provider would not accept")
                    .withDetail("failedRecently", failedRecently)
                    .withDetail("failed", failed)
                    .withDetail("pending", outbox.countByStatus(OutboxStatus.PENDING))
                    .build();
        }
        return Health.up()
                .withDetail("pending", outbox.countByStatus(OutboxStatus.PENDING))
                .withDetail("failed", failed)
                .build();
    }
}
