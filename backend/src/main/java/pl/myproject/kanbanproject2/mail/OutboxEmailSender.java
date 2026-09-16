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
 * The sender the application gets: it writes the message to a table and returns. A table rather
 * than Service Bus is what lets the row join the caller's own transaction, so an account and the
 * mail announcing it commit together or not at all; Service Bus can go behind {@link OutboxRelay}
 * later without any caller noticing.
 *
 * <p><b>{@code @Primary}</b>, so callers get this one. The other {@code EmailSender} bean is the
 * transport {@code EmailConfiguration} builds as {@code mailTransport}; only the relay asks for
 * that one by name.
 *
 * <p>What changes for a caller is the failure: {@code POST /api/auth/register} used to hold its
 * request thread for the round trip to Azure and answer {@code 500 EMAIL_SEND_FAILED} on a refusal;
 * it now returns as soon as the row is written. {@link EmailDeliveryException} still means "the
 * message could not be accepted", just moved from "a provider took it" to "it is written down".
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

    /**
     * @return always {@code null}: no provider has seen the message yet. The id is written onto the
     *     row later, when {@link OutboxRelay} posts it.
     */
    @Override
    public String send(EmailMessage message) {
        Instant now = clock.instant();
        try {
            OutboxEmail queued = outbox.save(OutboxEmail.queueing(message, now));
            log.debug("Queued outbox message {} for delivery", queued.getId());
        } catch (DataAccessException failure) {
            throw new EmailDeliveryException("the message could not be written to the outbox", failure);
        }
        return null;
    }
}
