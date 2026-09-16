package pl.myproject.kanbanproject2.mail;

/**
 * Where one queued message got to.
 *
 * <p>Stored as its name rather than its ordinal, because the one thing this table is for is being
 * read by a person asking why a mail did not arrive, and a column of small integers answers that
 * question badly.
 */
public enum OutboxStatus {

    /** Written and not yet accepted by the provider. The relay claims only these. */
    PENDING,

    /**
     * Claimed by a relay that is posting it now. The claim query looks only at {@link #PENDING}, so
     * a row here is invisible to every other relay. It carries a lease in {@code next_attempt_at}; a
     * lapsed one is taken back to {@link #PENDING} by the next pass, so a relay dying mid-batch
     * doesn't silently swallow it.
     */
    SENDING,

    /** The provider took it. Not the same as delivered - see MAIL-03. */
    SENT,

    /** Refused often enough that the relay stopped trying. {@code lastError} says what it said. */
    FAILED,

    /**
     * There was no mail account configured when the relay reached it. Recording that as
     * {@code SENT} would make the one table whose job is to be truthful about mail lie about it,
     * in exactly the environment (a fresh clone, CI) where somebody is most likely to be reading it.
     */
    DROPPED
}
