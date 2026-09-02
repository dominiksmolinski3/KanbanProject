package pl.myproject.kanbanproject2.config;

import lombok.extern.slf4j.Slf4j;
import pl.myproject.kanbanproject2.service.EmailMessage;
import pl.myproject.kanbanproject2.service.EmailSender;

/**
 * What runs when no mail account is configured: the message is dropped and the caller told nothing.
 *
 * <p>A fresh clone and the CI job have no Communication Services resource, and neither should have
 * to invent one to run the suite - the old SMTP sender made the same allowance by skipping the
 * connect when no username was set. Failing instead would turn a missing local secret into a
 * context that will not start.
 *
 * <p>Nothing about the message is logged. Subjects here carry task and board titles and the bodies
 * carry live verification and password-reset codes, and the only reason the SMTP configuration was
 * rewritten once already was that {@code mail.debug} had been left on and was writing the dialogue
 * to the application log. A developer who needs to know why no mail arrived has the startup warning
 * from {@link EmailConfiguration}, which names the properties to set.
 */
@Slf4j
public class DisabledEmailSender implements EmailSender {

    @Override
    public void send(EmailMessage message) {
        log.warn("Mail is not configured; a message was dropped rather than sent");
    }

    @Override
    public boolean deliversMessages() {
        return false;
    }
}
