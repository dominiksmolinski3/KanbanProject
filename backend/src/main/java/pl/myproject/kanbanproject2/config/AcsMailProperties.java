package pl.myproject.kanbanproject2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Where mail goes and how long a send may take.
 *
 * <p>This replaces {@code spring.mail.*} and the two {@code app.mail.*} knobs that tuned the held
 * SMTP connection. There is no connection to tune any more: Azure Communication Services takes a
 * message over HTTPS, so what is left to configure is the account, the address it is sent from, and
 * how long the caller is willing to wait.
 */
@ConfigurationProperties(prefix = "app.mail")
public record AcsMailProperties(

        /*
         * The Communication Services connection string - `endpoint=https://<name>.communication
         * .azure.com/;accesskey=<key>`. Blank turns mail off rather than failing to start, which
         * is what lets CI and a fresh clone run the suite without an Azure account.
         */
        String connectionString,

        /*
         * The MailFrom address, which has to be one the linked domain actually has: on an Azure
         * managed domain that is `DoNotReply@<guid>.azurecomm.net`, on a custom domain whatever
         * sender username was created under it. A wrong one is a 400 on the first send, not a
         * startup failure - there is nothing to check it against until a message is posted.
         */
        String senderAddress,

        /*
         * Per-attempt HTTP timeout. Ten seconds, matching the SMTP timeouts this replaced.
         */
        @DefaultValue("10s") Duration requestTimeout,

        /*
         * Retries on top of the first attempt, for transient failures only - the SDK's retry
         * policy does not replay a 4xx. A modest default now that sends happen on the outbox
         * relay's scheduler rather than a waiting request thread; raising it is safe.
         */
        @DefaultValue("1") int maxRetries,

        /*
         * The shared secret in the delivery-report webhook's URL, or blank for no webhook at all.
         * Event Grid has no account here, so the URL is the credential: blank means
         * MailDeliveryReportController answers 404 to everything, so this application's one
         * unauthenticated write does not exist unless somebody deliberately turns it on. Not part
         * of isConfigured() - mail sends fine without anyone listening for reports about it.
         */
        String deliveryReportKey) {

    /** Whether there is enough here to send anything at all. */
    public boolean isConfigured() {
        return StringUtils.hasText(connectionString) && StringUtils.hasText(senderAddress);
    }
}
