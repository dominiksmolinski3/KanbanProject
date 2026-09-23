package pl.myproject.kanbanproject2.mail;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

@Slf4j
@Component
public class OutboxRelay {

    static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);

    static final int BATCH_SIZE = 50;

    static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

    static final String DEAD_LETTER_MARKER = "MAIL_DEAD_LETTER";

    static final String DEAD_LETTER_COUNTER = "kanban.mail.outbox.dead_letters";

    private final OutboxEmailRepository outbox;
    private final OutboxClaimer claimer;
    private final EmailSender transport;
    private final Clock clock;
    private final Counter deadLetters;

    @Autowired
    public OutboxRelay(OutboxEmailRepository outbox,
                       OutboxClaimer claimer,
                       @Qualifier("mailTransport") EmailSender transport,
                       MeterRegistry registry) {
        this(outbox, claimer, transport, Clock.systemUTC(), registry);
    }

    OutboxRelay(OutboxEmailRepository outbox, OutboxClaimer claimer, EmailSender transport, Clock clock) {
        this(outbox, claimer, transport, clock, new SimpleMeterRegistry());
    }

    OutboxRelay(OutboxEmailRepository outbox, OutboxClaimer claimer, EmailSender transport, Clock clock,
               MeterRegistry registry) {
        this.outbox = outbox;
        this.claimer = claimer;
        this.transport = transport;
        this.clock = clock;
        this.deadLetters = registry.counter(DEAD_LETTER_COUNTER);
    }

    @Scheduled(fixedRate = 60000)
    public void deliverPending() {
        Instant now = clock.instant();
        reclaimLapsedClaims(now);

        List<OutboxEmail> due = claimer.claimDue(now, CLAIM_LEASE, BATCH_SIZE);
        if (due.isEmpty()) {
            return;
        }
        if (!transport.deliversMessages()) {
            log.warn("Mail is not configured; dropping {} queued message(s) rather than sending them", due.size());
            due.forEach(row -> row.dropped(now));
            outbox.saveAll(due);
            return;
        }
        for (OutboxEmail row : due) {
            deliver(row, now);
        }
    }

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
                deadLetters.increment();
            }
        }
    }

    private void deliver(OutboxEmail row, Instant now) {
        try {
            row.accepted(now, transport.send(row.asMessage()));
        } catch (EmailDeliveryException refusal) {
            row.refused(reasonOf(refusal), now, FIRST_BACKOFF);
            if (row.getStatus() == OutboxStatus.FAILED) {
                log.error("{}: outbox message {} refused {} times; giving up",
                        DEAD_LETTER_MARKER, row.getId(), row.getAttempts(), refusal);
                deadLetters.increment();
            } else {
                log.warn("Outbox message {} refused on attempt {}; retrying at {}",
                        row.getId(), row.getAttempts(), row.getNextAttemptAt());
            }
        }
        outbox.save(row);
    }

    private static String reasonOf(EmailDeliveryException refusal) {
        return refusal.getCause() == null ? refusal.getMessage() : String.valueOf(refusal.getCause().getMessage());
    }
}
