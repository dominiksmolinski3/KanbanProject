package pl.myproject.kanbanproject2.service;

/**
 * The transport mail leaves the application through.
 *
 * <p>{@link EmailService} composes messages; this posts them. Keeping the two apart is what let the
 * SMTP transport be replaced by the Azure Communication Services one without any caller noticing,
 * and it is the seam a queue goes behind: an implementation that writes the message to Service Bus
 * and returns, with a worker on the other side holding the real sender, changes nothing above this
 * line.
 */
public interface EmailSender {

    /**
     * Hands one composed message to the provider.
     *
     * @throws EmailDeliveryException if the provider would not take it.
     */
    void send(EmailMessage message);

    /**
     * Whether this transport actually posts what it is given.
     *
     * <p>Only the outbox relay asks. Everything else has no use for the answer - a caller that
     * behaved differently when mail was unconfigured would be a caller that fails on a fresh
     * clone, which is the thing {@code DisabledEmailSender} exists to prevent. The relay asks
     * because it has a row to update either way, and recording a dropped message as sent would
     * put a lie in the one table whose job is to be truthful about mail.
     */
    default boolean deliversMessages() {
        return true;
    }
}
