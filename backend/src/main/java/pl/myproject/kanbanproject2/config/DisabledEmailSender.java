package pl.myproject.kanbanproject2.config;

import lombok.extern.slf4j.Slf4j;
import pl.myproject.kanbanproject2.service.EmailMessage;
import pl.myproject.kanbanproject2.service.EmailSender;

/**
 * What runs when no mail account is configured: the message is dropped and the caller told
 * nothing, so a fresh clone or CI run starts rather than failing over a missing local secret.
 * Nothing about the message is logged, since subjects carry task titles and bodies carry live
 * verification codes; the startup warning from {@link EmailConfiguration} is the diagnosis path.
 */
@Slf4j
public class DisabledEmailSender implements EmailSender {

    @Override
    public String send(EmailMessage message) {
        log.warn("Mail is not configured; a message was dropped rather than sent");
        // No provider took it, so there is no provider id to hand back. A DROPPED row is never
        // going to be named by a delivery report.
        return null;
    }

    @Override
    public boolean deliversMessages() {
        return false;
    }
}
