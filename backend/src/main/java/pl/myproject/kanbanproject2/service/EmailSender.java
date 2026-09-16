package pl.myproject.kanbanproject2.service;

/**
 * The transport mail leaves the application through. {@link EmailService} composes messages; this
 * posts them - keeping the two apart is what let the SMTP transport be replaced by Azure
 * Communication Services with no caller noticing, and it's the seam a queue goes behind.
 */
public interface EmailSender {

    /**
     * Hands one composed message to the provider.
     *
     * <p><b>The return value is the provider's own id for the message</b>, which exists so a
     * delivery report arriving later, out of band, can be joined to it - without an id at
     * acceptance, "did the code reach them" is unanswerable. It is the join key and nothing else;
     * only {@link pl.myproject.kanbanproject2.mail.OutboxRelay} reads and stores it.
     *
     * <p>{@code null} is a legitimate answer meaning "this transport has no id to give" - true of
     * the disabled sender, the outbox itself, and a real send whose id couldn't be read. A row with
     * no id simply can't be matched to a report; it is not a failure of the send.
     *
     * @return the provider's id for the message, or {@code null} if this transport has none.
     * @throws EmailDeliveryException if the provider would not take it.
     */
    String send(EmailMessage message);

    /**
     * Whether this transport actually posts what it is given. Only the outbox relay asks - a caller
     * that behaved differently when mail was unconfigured would fail on a fresh clone, which
     * {@code DisabledEmailSender} exists to prevent. The relay asks because it has a row to update
     * either way, and recording a dropped message as sent would put a lie in the one table whose
     * job is to be truthful about mail.
     */
    default boolean deliversMessages() {
        return true;
    }
}
