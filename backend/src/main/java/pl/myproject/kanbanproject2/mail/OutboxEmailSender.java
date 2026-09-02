package pl.myproject.kanbanproject2.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.service.EmailDeliveryException;
import pl.myproject.kanbanproject2.service.EmailMessage;
import pl.myproject.kanbanproject2.service.EmailSender;

import java.time.Clock;
import java.time.Instant;

/**
 * The sender the application gets: it writes the message down and returns.
 *
 * <p>This is the implementation {@code EmailSender}'s own comment has described since the transport
 * swap - "an implementation that writes the message somewhere and returns, with a worker on the
 * other side holding the real sender". The somewhere is a table rather than Service Bus, and that
 * is the difference between an enqueue that agrees with the database and one that does not: the row
 * joins whatever transaction the caller is already in, so an account and the mail announcing it
 * commit together or not at all. Service Bus can go behind {@link OutboxRelay} later without any
 * caller noticing, which was the point of the seam.
 *
 * <p><b>{@code @Primary}, so callers get this one.</b> There are two {@code EmailSender} beans:
 * this, and the transport {@code EmailConfiguration} builds under the name {@code mailTransport}.
 * Everything above the queue takes the primary; only the relay asks for the transport by name.
 *
 * <p>What changes for a caller is the failure. {@code POST /api/auth/register} used to hold its
 * request thread open for the round trip to Azure and answer {@code 500 EMAIL_SEND_FAILED} if the
 * provider refused - an account written, a code stored, and a signup reported as broken for a
 * condition the person signing up could do nothing about. It now returns as soon as the row is
 * written. {@link EmailDeliveryException} is still thrown from here and still means the same thing:
 * the message could not be accepted for sending. Only the bar has moved, from "a provider took it"
 * to "it is written down" - and a database that will not take the row is a signup that was not
 * going to work either.
 */
@Slf4j
@Component
@Primary
public class OutboxEmailSender implements EmailSender {

    private final OutboxEmailRepository outbox;
    private final Clock clock;

    @Autowired
    public OutboxEmailSender(OutboxEmailRepository outbox) {
        this(outbox, Clock.systemUTC());
    }

    OutboxEmailSender(OutboxEmailRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void send(EmailMessage message) {
        Instant now = clock.instant();
        try {
            OutboxEmail queued = outbox.save(OutboxEmail.queueing(message, now));
            log.debug("Queued outbox message {} for delivery", queued.getId());
        } catch (DataAccessException failure) {
            throw new EmailDeliveryException("the message could not be written to the outbox", failure);
        }
    }
}
