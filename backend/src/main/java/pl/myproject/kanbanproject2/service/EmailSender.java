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
     * Hands one message to the provider.
     *
     * @throws EmailDeliveryException if the provider would not take it.
     */
    void send(String to, String subject, String htmlBody);
}
