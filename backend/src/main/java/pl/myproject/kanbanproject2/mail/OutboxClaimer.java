package pl.myproject.kanbanproject2.mail;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The short transaction that takes rows out of the queue, separate from the long stretch that posts
 * them.
 *
 * <p>It exists as its own bean because that is the only way the transaction is real. Spring's
 * transaction management is proxy-based: a {@code @Transactional} method called from inside the
 * same object is called on {@code this} and not through the proxy, so a claim written as a private
 * method of {@link OutboxRelay} would compile, read correctly, and run with no transaction at all -
 * which is precisely the failure this class exists to prevent. Two relays would then both select
 * the same rows, because the lock each took would have been released at the end of its own
 * statement, before either had written {@code SENDING}.
 *
 * <p><b>The boundary is the point.</b> Inside it: one select that locks and one update that marks.
 * Outside it: every HTTPS call. {@link OutboxRelay} explains at length why the sending must not be
 * transactional - a connection held across fifty round trips, and forty-nine sent messages rolled
 * back because the fiftieth was refused - and that reasoning is unchanged. What was missing was a
 * transaction anywhere at all; claiming needs one and sending must not have one, so they are two
 * methods on two objects.
 *
 * <p>Both methods are {@code public} for the same proxying reason: {@code AnnotationTransaction
 * AttributeSource} considers public methods only, and {@code @Transactional} on a package-private
 * one is silently ignored.
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
     * Puts back rows whose claim lapsed, and returns them so that the caller can say so out loud.
     *
     * <p>Deliberately a separate pass rather than something folded into {@link #claimDue}: a row
     * recovered here is evidence that a relay died holding a batch, which is worth a log line and
     * is not worth a duplicate send one second later. It goes back to {@code PENDING} due now, so
     * the next pass - a minute away - takes it in the ordinary way.
     */
    @Transactional
    public List<OutboxEmail> reclaimLapsed(Instant now, int limit) {
        List<OutboxEmail> lapsed = outbox.claimBatch(OutboxStatus.SENDING.name(), now, limit);
        lapsed.forEach(row -> row.abandoned(now));
        return outbox.saveAll(lapsed);
    }
}
