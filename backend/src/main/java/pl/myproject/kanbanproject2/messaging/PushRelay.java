package pl.myproject.kanbanproject2.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class PushRelay {

    static final Duration LEASE = Duration.ofMinutes(2);

    static final Duration FIRST_BACKOFF = Duration.ofSeconds(30);

    static final int BATCH_SIZE = 50;

    static final int MAX_ATTEMPTS = 5;

    static final String SENT_COUNTER = "kanban.push.sent";

    static final String FAILED_COUNTER = "kanban.push.failed";

    private final PushOutboxClaimer claimer;
    private final PushOutboxRepository outbox;
    private final PushEndpointRepository endpoints;
    private final Map<PushKind, PushSender> senders = new EnumMap<>(PushKind.class);
    private final TransactionOperations transactions;
    private final Clock clock;
    private final Counter sent;
    private final Counter failed;

    @Autowired
    public PushRelay(PushOutboxClaimer claimer, PushOutboxRepository outbox, PushEndpointRepository endpoints,
                     List<PushSender> senders, TransactionOperations transactions, MeterRegistry registry) {
        this(claimer, outbox, endpoints, senders, transactions, Clock.systemUTC(), registry);
    }

    PushRelay(PushOutboxClaimer claimer, PushOutboxRepository outbox, PushEndpointRepository endpoints,
              List<PushSender> senders, TransactionOperations transactions, Clock clock) {
        this(claimer, outbox, endpoints, senders, transactions, clock, new SimpleMeterRegistry());
    }

    PushRelay(PushOutboxClaimer claimer, PushOutboxRepository outbox, PushEndpointRepository endpoints,
              List<PushSender> senders, TransactionOperations transactions, Clock clock, MeterRegistry registry) {
        this.claimer = claimer;
        this.outbox = outbox;
        this.endpoints = endpoints;
        senders.forEach(sender -> this.senders.put(sender.kind(), sender));
        this.transactions = transactions;
        this.clock = clock;
        this.sent = registry.counter(SENT_COUNTER);
        this.failed = registry.counter(FAILED_COUNTER);
    }

    @Scheduled(fixedDelay = 2000)
    public void deliverDue() {
        if (senders.values().stream().noneMatch(PushSender::configured)) {
            return;
        }
        Instant now = clock.instant();
        for (PushOutbox row : claimer.claimDue(now, LEASE, BATCH_SIZE)) {
            deliver(row, now);
        }
    }

    private void deliver(PushOutbox row, Instant now) {
        PushEndpoint endpoint = row.getEndpoint();
        PushSender sender = senders.get(endpoint.getKind());
        if (sender == null || !sender.configured()) {
            transactions.executeWithoutResult(status -> outbox.deleteDelivered(row.getId(), row.getSeq()));
            return;
        }
        try {
            sender.send(endpoint, row.getConversationId(), row.getSeq());
            sent.increment();
            transactions.executeWithoutResult(status -> outbox.deleteDelivered(row.getId(), row.getSeq()));
        } catch (PushSender.PushException refusal) {
            failed.increment();
            if (refusal.isGone()) {
                log.info("Push endpoint {} is gone; removing it", endpoint.getId());
                transactions.executeWithoutResult(status -> endpoints.deleteById(endpoint.getId()));
                return;
            }
            row.failed(now, FIRST_BACKOFF);
            if (row.getAttempts() >= MAX_ATTEMPTS) {
                log.error("PUSH_DEAD_LETTER: notification {} for endpoint {} failed {} times; giving up: {}",
                        row.getId(), endpoint.getId(), row.getAttempts(), refusal.getMessage());
                transactions.executeWithoutResult(status -> outbox.deleteById(row.getId()));
            } else {
                log.warn("Push to endpoint {} failed on attempt {}: {}", endpoint.getId(), row.getAttempts(),
                        refusal.getMessage());
                transactions.executeWithoutResult(status -> outbox.save(row));
            }
        }
    }
}
