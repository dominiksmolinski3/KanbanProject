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
     * Claimed by a relay that is posting it now.
     *
     * <p>The status a row passes through between being selected and being answered for, and the
     * whole of what keeps a second replica off it: the claim query looks only at {@link #PENDING},
     * so a row in this state is invisible to every other relay. It is written in a short
     * transaction of its own - the row cannot stay locked while an HTTPS call is made, because the
     * relay would then be holding a connection across fifty of them.
     *
     * <p><b>A row here is not a row being sent forever.</b> A relay that dies mid-batch leaves its
     * claims behind with nothing to answer them, so the claim carries a lease in
     * {@code next_attempt_at} and a lapsed one is taken back to {@link #PENDING} by the next pass.
     * That is what makes the state safe to enter; without it a crash would silently swallow the
     * batch it was holding.
     */
    SENDING,

    /** The provider took it. Not the same as delivered - see MAIL-03. */
    SENT,

    /** Refused often enough that the relay stopped trying. {@code lastError} says what it said. */
    FAILED,

    /**
     * There was no mail account configured when the relay reached it.
     *
     * <p>A fresh clone and the CI job have no Communication Services resource, and the transport
     * they get drops messages on purpose. Recording that as {@code SENT} would make the one table
     * whose job is to be truthful about mail lie about it in exactly the environment where
     * somebody is most likely to be reading it.
     */
    DROPPED
}
