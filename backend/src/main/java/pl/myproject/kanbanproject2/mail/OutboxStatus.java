package pl.myproject.kanbanproject2.mail;

/**
 * Where one queued message got to.
 *
 * <p>Stored as its name rather than its ordinal, because the one thing this table is for is being
 * read by a person asking why a mail did not arrive, and a column of small integers answers that
 * question badly.
 */
public enum OutboxStatus {

    /** Written and not yet accepted by the provider. The relay looks only at these. */
    PENDING,

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
