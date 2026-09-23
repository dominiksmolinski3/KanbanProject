package pl.myproject.kanbanproject2.mail;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

// Its own bean so @Transactional goes through the proxy; called on OutboxRelay's this, the claim would hold no lock.
@Component
public class OutboxClaimer {

    private final OutboxEmailRepository outbox;

    public OutboxClaimer(OutboxEmailRepository outbox) {
        this.outbox = outbox;
    }

    @Transactional
    public List<OutboxEmail> claimDue(Instant now, Duration lease, int limit) {
        List<OutboxEmail> due = outbox.claimBatch(OutboxStatus.PENDING.name(), now, limit);
        due.forEach(row -> row.claimed(now, lease));
        return outbox.saveAll(due);
    }

    @Transactional
    public List<OutboxEmail> reclaimLapsed(Instant now, int limit) {
        List<OutboxEmail> lapsed = outbox.claimBatch(OutboxStatus.SENDING.name(), now, limit);
        lapsed.forEach(row -> row.abandoned(now));
        return outbox.saveAll(lapsed);
    }
}
