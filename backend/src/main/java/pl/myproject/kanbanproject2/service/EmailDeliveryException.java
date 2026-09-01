package pl.myproject.kanbanproject2.service;

/**
 * The provider would not take a message.
 *
 * <p>It replaces {@code jakarta.mail.MessagingException}, which used to reach the callers of {@link
 * EmailService} and tied every one of them to SMTP being the transport. Unchecked, because the two
 * call sites that care already catch it deliberately - to answer {@code EMAIL_SEND_FAILED} - and
 * the one that does not ({@code DeadlineNotifier}) swallows everything on purpose, so a checked
 * exception bought a compiler reminder nobody needed.
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
