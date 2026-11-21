package pl.myproject.kanbanproject2.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

// Its own bean so @Transactional goes through the proxy; called on PushRelay's this, the claim would hold no lock.
@Component
public class PushOutboxClaimer {

    private final PushOutboxRepository outbox;

    public PushOutboxClaimer(PushOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional
    public List<PushOutbox> claimDue(Instant now, Duration lease, int limit) {
        List<PushOutbox> due = outbox.claimBatch(now, limit);
        due.forEach(row -> row.leased(now, lease));
        return outbox.saveAll(due);
    }
}
