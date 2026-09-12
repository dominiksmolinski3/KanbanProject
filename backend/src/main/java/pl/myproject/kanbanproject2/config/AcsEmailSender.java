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
 * Posts messages to Azure Communication Services over HTTPS.
 *
 * <p>What this replaced was a class that existed entirely to keep one SMTP connection logged in,
 * ping it every four minutes, replace it before Gmail expired it, and tell a dropped link apart
 * from a rejected message so it could retry the first and not the second. None of that has an
 * equivalent here. There is no connection to hold: each send is a request on a pooled HTTPS
 * connection the SDK manages, transient failures are the pipeline's retry policy rather than ours,
 * and concurrency is the pool's problem rather than a lock around a single {@code Transport}.
 *
 * <p><b>Why the returned poller is dropped.</b> {@code beginSend} looks like it starts something
 * that has to be waited on, and ignoring a poller usually means nothing happened. Here the opposite
 * is true: {@code EmailClient.beginSend} builds a {@code SyncOverAsyncPoller}, whose constructor
 * runs the activation operation - the POST - before it returns. So by the time {@code beginSend}
 * hands back a poller, Azure has been given the message and has answered {@code 202}, and anything
 * it objected to (a bad key, a sender address the domain does not have, a malformed recipient) has
 * already come back as an exception on this line.
 *
 * <p>Polling to <em>completion</em> would be waiting for delivery, which is Azure's job and takes
 * as long as the recipient's mail server takes. Nothing here does that, and nothing here should.
 *
 * <p><b>One poll, though, and that is a change of position worth writing down.</b> This class used
 * to drop the poller untouched, on the argument that "a signup has no use for the answer and every
 * reason not to hold a request thread open for it". The first half is still true. The second half
 * stopped being true when the outbox landed: no signup has waited for a send since, because sends
 * happen on {@code OutboxRelay}'s scheduler thread and the request thread ends at a committed row.
 * The constraint that made a second round trip unacceptable was removed a revision later by
 * somebody solving a different problem, and nobody noticed until there was a reason to look.
 *
 * <p>What the one poll buys is the operation id, which is the id an Event Grid delivery report
 * names - and without it a report is a fact about an address rather than about a message. It is a
 * single {@code GET} on the operation URL, not a loop: {@link SyncPoller#poll()} returns the first
 * response and this never asks for a second. It is also <b>best-effort</b>: the message is already
 * accepted by the time it runs, so a failure to read the id is logged and answered with
 * {@code null} rather than turned into a send that reports itself as refused. Measured rather than
 * assumed - {@code AcsEmailSenderTest} counts the requests that leave, and fails if activation
 * stops being eager or if this ever walks the poller to completion.
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
     * The id Azure gave this message, or {@code null} if it could not be read.
     *
     * <p>Everything above this line has already happened - the message is with Azure and will be
     * delivered or not regardless of what this returns. So nothing here may throw: a network blip
     * on the operation lookup must not turn a message that <em>was</em> accepted into a row the
     * relay retries, which would post it a second time and mail somebody twice.
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
