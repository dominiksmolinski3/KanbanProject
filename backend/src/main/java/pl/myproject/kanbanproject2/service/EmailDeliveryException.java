package pl.myproject.kanbanproject2.service;

/**
 * The provider would not take a message. Replaces {@code jakarta.mail.MessagingException}, which
 * tied every caller of {@link EmailService} to SMTP being the transport. Unchecked, since the two
 * call sites that care already catch it deliberately and the other ({@code DeadlineNotifier})
 * swallows everything on purpose - a checked exception bought a compiler reminder nobody needed.
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
