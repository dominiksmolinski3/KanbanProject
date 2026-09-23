package pl.myproject.kanbanproject2.config;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.models.EmailSendResult;
import com.azure.core.util.polling.PollResponse;
import com.azure.core.util.polling.SyncPoller;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.myproject.kanbanproject2.service.EmailDeliveryException;
import pl.myproject.kanbanproject2.service.EmailMessage;
import pl.myproject.kanbanproject2.service.EmailSender;

@Slf4j
@RequiredArgsConstructor
public class AcsEmailSender implements EmailSender {

    private final EmailClient client;
    private final String senderAddress;

    @Override
    public String send(EmailMessage message) {
        com.azure.communication.email.models.EmailMessage posted =
                new com.azure.communication.email.models.EmailMessage()
                        .setSenderAddress(senderAddress)
                        .setToRecipients(message.to())
                        .setSubject(message.subject())
                        .setBodyHtml(message.htmlBody())
                        .setBodyPlainText(message.textBody());

        SyncPoller<EmailSendResult, EmailSendResult> operation;
        try {
            operation = client.beginSend(posted);
        } catch (RuntimeException failure) {
            throw new EmailDeliveryException("Azure Communication Services would not accept the message", failure);
        }
        log.debug("Azure Communication Services accepted a message for delivery");
        return operationIdOf(operation);
    }

    private String operationIdOf(SyncPoller<EmailSendResult, EmailSendResult> operation) {
        try {
            PollResponse<EmailSendResult> first = operation.poll();
            EmailSendResult result = first.getValue();
            return result == null ? null : result.getId();
        } catch (RuntimeException unreadable) {
            log.warn("Azure accepted the message but would not name it; a delivery report for it "
                    + "will have nothing to match against", unreadable);
            return null;
        }
    }
}
