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

/**
 * Posts messages to Azure Communication Services over HTTPS. Unlike the held SMTP connection this
 * replaced, there is no connection to manage: each send is a request on a pooled HTTPS connection,
 * with retries and concurrency handled by the SDK.
 *
 * <p><b>The poller is polled once, not dropped and not run to completion.</b>
 * {@code EmailClient.beginSend} builds a {@code SyncOverAsyncPoller} whose constructor performs the
 * activation POST before returning, so by the time it hands back a poller Azure has already
 * accepted or rejected the message. One {@link SyncPoller#poll()} call reads back the operation id
 * an Event Grid delivery report needs to match against - a single {@code GET}, never a loop to
 * completion (which would wait on delivery rather than acceptance). It is best-effort: a failure to
 * read the id is logged and returns {@code null} rather than reporting an accepted send as refused.
 * {@code AcsEmailSenderTest} fails if activation stops being eager or this ever polls to completion.
 */
@Slf4j
@RequiredArgsConstructor
public class AcsEmailSender implements EmailSender {

    private final EmailClient client;
    private final String senderAddress;

    /**
     * The provider's own {@code EmailMessage} is written out in full because this application has
     * one of its own, and the domain type is the one that belongs in the signature - the same
     * choice the entity package makes between {@code Column} and {@code jakarta.persistence.Column}.
     */
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
            // Deliberately not logged here: both callers that care log it with the context that
            // makes it useful, and the one that does not is swallowing it on purpose.
            throw new EmailDeliveryException("Azure Communication Services would not accept the message", failure);
        }
        log.debug("Azure Communication Services accepted a message for delivery");
        return operationIdOf(operation);
    }

    /**
     * The id Azure gave this message, or {@code null} if it could not be read. Nothing here may
     * throw: the message is already accepted, so a lookup failure must not turn it into a row the
     * relay retries and mails a second time.
     */
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
