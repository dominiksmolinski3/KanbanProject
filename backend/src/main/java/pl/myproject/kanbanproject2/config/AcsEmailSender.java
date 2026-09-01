package pl.myproject.kanbanproject2.config;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.models.EmailMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pl.myproject.kanbanproject2.service.EmailDeliveryException;
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
 * <p>Polling past that would be waiting for <em>delivery</em>, which is Azure's job and takes as
 * long as the recipient's mail server takes. A signup has no use for the answer and every reason
 * not to hold a request thread open for it, so acceptance is where this stops. The consequence
 * worth naming: a message accepted here and bounced later is not reported anywhere - a delivery
 * report subscription on the Communication Services resource is what surfaces that, and there is
 * not one yet.
 */
@Slf4j
@RequiredArgsConstructor
public class AcsEmailSender implements EmailSender {

    private final EmailClient client;
    private final String senderAddress;

    @Override
    public void send(String to, String subject, String htmlBody) {
        EmailMessage message = new EmailMessage()
                .setSenderAddress(senderAddress)
                .setToRecipients(to)
                .setSubject(subject)
                .setBodyHtml(htmlBody);

        try {
            client.beginSend(message);
        } catch (RuntimeException failure) {
            // Deliberately not logged here: both callers that care log it with the context that
            // makes it useful, and the one that does not is swallowing it on purpose.
            throw new EmailDeliveryException("Azure Communication Services would not accept the message", failure);
        }
        log.debug("Azure Communication Services accepted a message for delivery");
    }
}
