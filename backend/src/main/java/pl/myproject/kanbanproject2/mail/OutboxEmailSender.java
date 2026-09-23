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
