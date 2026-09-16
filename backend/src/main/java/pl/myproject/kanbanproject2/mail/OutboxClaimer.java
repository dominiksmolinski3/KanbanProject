package pl.myproject.kanbanproject2.mail;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The short transaction that takes rows out of the queue, separate from the long stretch that posts
 * them. It exists as its own bean because Spring's transaction management is proxy-based: a
 * {@code @Transactional} method called from inside {@link OutboxRelay} would run on {@code this},
 * with no transaction at all, and two relays would then select the same rows since each lock
 * releases at the end of its own statement before either writes {@code SENDING}.
 *
 * <p>Both methods are {@code public} for the same proxying reason - {@code @Transactional} on a
 * package-private method is silently ignored.
 */
@Component
public class OutboxClaimer {

    private final OutboxEmailRepository outbox;

    public OutboxClaimer(OutboxEmailRepository outbox) {
        this.outbox = outbox;
    }

    /**
     * Takes up to {@code limit} due rows for this relay and nobody else, and returns them.
     *
     * @param lease how long the claim holds before another relay may assume this one died.
     */
    @Transactional
    public List<OutboxEmail> claimDue(Instant now, Duration lease, int limit) {
        List<OutboxEmail> due = outbox.claimBatch(OutboxStatus.PENDING.name(), now, limit);
        due.forEach(row -> row.claimed(now, lease));
        return outbox.saveAll(due);
    }

    /**
     * Puts back rows whose claim lapsed, and returns them so the caller can log it. A separate pass
     * rather than folded into {@link #claimDue}, so a recovered row is due next pass rather than
     * resent a second later.
     */
    @Transactional
    public List<OutboxEmail> reclaimLapsed(Instant now, int limit) {
        List<OutboxEmail> lapsed = outbox.claimBatch(OutboxStatus.SENDING.name(), now, limit);
        lapsed.forEach(row -> row.abandoned(now));
        return outbox.saveAll(lapsed);
    }
}
