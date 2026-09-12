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
     * <p><b>The return value is the provider's own id for the message</b>, which exists for one
     * reason: a delivery report arrives later, out of band, naming the message it is about. Without
     * an id written down at the moment of acceptance there is nothing to join that report to, and
     * "did the code reach them" stays unanswerable however much Azure knows. It is the join key and
     * nothing else - no caller reads it except {@link pl.myproject.kanbanproject2.mail.OutboxRelay},
     * which stores it.
     *
     * <p>{@code null} is a legitimate answer and means "this transport has no id to give", which is
     * true of the disabled sender, true of the outbox itself (a row is not a provider message), and
     * true of a real send whose id could not be read. A row with no id simply cannot be matched to
     * a report; it is not a failure of the send.
     *
     * @return the provider's id for the message, or {@code null} if this transport has none.
     * @throws EmailDeliveryException if the provider would not take it.
     */
    String send(EmailMessage message);

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
