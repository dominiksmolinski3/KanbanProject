package pl.myproject.kanbanproject2.config;

import lombok.extern.slf4j.Slf4j;
import pl.myproject.kanbanproject2.service.EmailMessage;
import pl.myproject.kanbanproject2.service.EmailSender;

@Slf4j
public class DisabledEmailSender implements EmailSender {

    @Override
    public String send(EmailMessage message) {
        log.warn("Mail is not configured; a message was dropped rather than sent");
        return null;
    }

    @Override
    public boolean deliversMessages() {
        return false;
    }
}
