package pl.myproject.kanbanproject2.config;

import jakarta.annotation.PreDestroy;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@link JavaMailSenderImpl} that logs in once and keeps the connection.
 *
 * <p>The stock implementation opens a TCP connection, negotiates STARTTLS and authenticates for
 * every {@code send}, then closes it again - so the handshake sat on the critical path of every
 * signup, every resend and every password reset: a second or so of round trips to Gmail before the
 * response could be written, on routes that are rate limited precisely because people retry them.
 *
 * <p>Here the transport is opened at {@link ApplicationReadyEvent} and held. {@code
 * SMTPTransport.isConnected()} answers with a {@code NOOP} on the wire rather than from a local
 * flag, so the liveness check before each send is a real one, and the scheduled {@link #keepAlive()}
 * doubles as the ping that stops the server retiring an idle connection. A connection found dead is
 * replaced rather than reported.
 *
 * <p>One connection means one sender at a time: {@link #doSend} holds a lock for the whole batch.
 * This project sends a handful of messages a minute at most, so serialising them costs nothing next
 * to the handshake it removes - and a {@code Transport} is not safe to share between threads
 * anyway.
 *
 * <p>Nothing here is fatal. A connection that cannot be opened at startup is logged and retried by
 * the next send, which is the behaviour every caller already had.
 */
@Slf4j
public class PersistentSmtpMailSender extends JavaMailSenderImpl {

    private static final String HEADER_MESSAGE_ID = "Message-ID";

    private final Object lock = new Object();

    private Transport transport;

    /** When {@link #transport} was opened, which is what {@link #keepAlive()} ages it against. */
    private long openedAt;

    private long maxConnectionAgeMs = 600000;

    /**
     * How long a connection may be held before the keep-alive replaces it. A server retires one on
     * its own schedule whatever this says - Gmail answers {@code 421 Connection expired} - and the
     * retry in {@link #sendOverHeldConnection} is what makes that survivable. This is what keeps it
     * rare, and keeps the reconnect on the scheduler thread rather than in somebody's signup.
     */
    public void setMaxConnectionAgeMs(long maxConnectionAgeMs) {
        this.maxConnectionAgeMs = maxConnectionAgeMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openOnStartup() {
        renew("startup");
    }

    /**
     * Keeps the held connection live, and reopens it when the server has hung up - which it will,
     * unprompted, on an idle connection. Four minutes by default, comfortably inside the idle
     * timeout of every SMTP server this is likely to talk to.
     *
     * <p>A connection past {@link #setMaxConnectionAgeMs} is replaced rather than pinged. Staying
     * alive is not the same as staying usable - Gmail expires a connection on its own terms however
     * faithfully it has been pinged - and the difference between meeting that here and meeting it
     * inside {@code sendMessage} is the difference between a log line and a signup that waits for a
     * reconnect it did not expect.
     */
    @Scheduled(fixedRateString = "${app.mail.keep-alive-interval-ms:240000}",
            initialDelayString = "${app.mail.keep-alive-interval-ms:240000}")
    public void keepAlive() {
        synchronized (lock) {
            if (transport != null && System.currentTimeMillis() - openedAt >= maxConnectionAgeMs) {
                log.info("Replacing the SMTP connection before the server retires it");
                closeQuietly(transport);
                transport = null;
            }
        }
        renew("keep-alive");
    }

    @PreDestroy
    public void closeConnection() {
        synchronized (lock) {
            closeQuietly(transport);
            transport = null;
        }
    }

    /**
     * Mirrors {@link JavaMailSenderImpl#doSend} - the per-message preparation and the {@link
     * MailSendException} carrying the messages that failed are its contract, not this one's - and
     * differs in a single respect: the transport outlives the call.
     */
    @Override
    protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) throws MailException {
        Map<Object, Exception> failedMessages = new LinkedHashMap<>();

        synchronized (lock) {
            for (int i = 0; i < mimeMessages.length; i++) {
                MimeMessage mimeMessage = mimeMessages[i];
                Object original = (originalMessages != null ? originalMessages[i] : mimeMessage);
                try {
                    prepare(mimeMessage);
                    sendOverHeldConnection(mimeMessage);
                } catch (Exception ex) {
                    failedMessages.put(original, ex);
                }
            }
        }

        if (!failedMessages.isEmpty()) {
            throw new MailSendException(failedMessages);
        }
    }

    /** Opens the connection when there is none, and answers the one there is otherwise. */
    protected Transport connection() throws MessagingException {
        if (transport != null) {
            if (isAlive(transport)) {
                return transport;
            }
            log.info("The held SMTP connection is gone; opening another");
            closeQuietly(transport);
            transport = null;
        }

        Transport opened = openTransport();
        log.info("SMTP connection to {}:{} open and authenticated", getHost(), getPort());
        transport = opened;
        openedAt = System.currentTimeMillis();
        return opened;
    }

    /**
     * The seam the tests replace: everything above this line is connection lifecycle, and only this
     * needs a server on the other end.
     */
    protected Transport openTransport() throws MessagingException {
        return connectTransport();
    }

    private void renew(String reason) {
        if (!StringUtils.hasText(getUsername())) {
            log.debug("No SMTP credentials configured; skipping the {} connect", reason);
            return;
        }
        synchronized (lock) {
            try {
                connection();
            } catch (MessagingException e) {
                log.warn("Could not open the SMTP connection ({}); the next message will try again", reason, e);
            }
        }
    }

    private void prepare(MimeMessage mimeMessage) throws MessagingException {
        if (mimeMessage.getSentDate() == null) {
            mimeMessage.setSentDate(new Date());
        }
        String messageId = mimeMessage.getMessageID();
        mimeMessage.saveChanges();
        if (messageId != null) {
            mimeMessage.setHeader(HEADER_MESSAGE_ID, messageId);
        }
    }

    /**
     * Sends, and retries once on a connection that died between the liveness check and the write -
     * a race the stock implementation cannot lose because it never reuses a connection, and this
     * one can.
     */
    private void sendOverHeldConnection(MimeMessage mimeMessage) throws MessagingException {
        Address[] recipients = mimeMessage.getAllRecipients();
        Address[] addresses = (recipients != null ? recipients : new Address[0]);

        try {
            connection().sendMessage(mimeMessage, addresses);
        } catch (MessagingException failure) {
            if (!isWorthRetrying(failure)) {
                throw failure;
            }
            log.warn("SMTP connection was gone by the time the message went out; reconnecting and retrying once",
                    failure);
            closeQuietly(transport);
            transport = null;
            connection().sendMessage(mimeMessage, addresses);
        }
    }

    /**
     * Whether the failure was the connection rather than the message - which is the only question
     * worth asking here, and specifically not "what type is the exception".
     *
     * <p>It was the type at first, and that was wrong in production within the hour: Gmail retires
     * a connection with {@code 421 4.7.0 Connection expired, try reconnecting}, and Angus reports
     * that as {@code SMTPSendFailedException}, a subclass of {@link SendFailedException}. Reading
     * the class as "the server rejected this message" turned the one failure this class exists to
     * absorb into a failed signup, and the {@code try reconnecting} in the server's own words went
     * unheeded.
     *
     * <p>So the liveness of the connection decides. A server that rejects a message stays on the
     * line to say so, and a rejection answered over a live connection is rethrown untouched - the
     * message is the problem, and sending it again would deliver it twice. A connection that is
     * gone carried nothing, with one exception worth checking: a partial send that some recipients
     * already took is a delivery, not a drop.
     */
    private boolean isWorthRetrying(MessagingException failure) {
        if (failure instanceof SendFailedException sendFailed && reachedSomeone(sendFailed)) {
            return false;
        }
        return !isAlive(transport);
    }

    private static boolean reachedSomeone(SendFailedException failure) {
        Address[] delivered = failure.getValidSentAddresses();
        return delivered != null && delivered.length > 0;
    }

    /** For {@code SMTPTransport} this is a {@code NOOP} on the wire, not a local flag. */
    private static boolean isAlive(Transport transport) {
        try {
            return transport != null && transport.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private static void closeQuietly(Transport transport) {
        if (transport == null) {
            return;
        }
        try {
            transport.close();
        } catch (Exception e) {
            log.debug("Closing the SMTP connection failed; dropping it anyway", e);
        }
    }
}
