package pl.myproject.kanbanproject2.config;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What is worth testing here is not that mail is sent - {@code JavaMailSenderImpl} does that - but
 * that the connection lives across sends, and that the ways it can die are all handled: a server
 * that hangs up while idle, one that hangs up mid-send, and one that simply rejects a message.
 *
 * <p>The seam is {@link PersistentSmtpMailSender#openTransport()}; everything below it needs a real
 * SMTP server, everything above it is the lifecycle these tests are about.
 */
class PersistentSmtpMailSenderTest {

    private static class TestableSender extends PersistentSmtpMailSender {

        private final List<Transport> opened = new ArrayList<>();
        private final List<Transport> toOpen = new ArrayList<>();
        private MessagingException openFailure;

        void willOpen(Transport... transports) {
            toOpen.addAll(List.of(transports));
        }

        void willFailToOpen(MessagingException failure) {
            this.openFailure = failure;
        }

        @Override
        protected Transport openTransport() throws MessagingException {
            if (openFailure != null) {
                throw openFailure;
            }
            Transport next = toOpen.remove(0);
            opened.add(next);
            return next;
        }
    }

    private TestableSender sender;

    @BeforeEach
    void setUp() {
        sender = new TestableSender();
        sender.setHost("smtp.example.test");
        sender.setPort(587);
        sender.setUsername("someone@example.test");
        sender.setPassword("secret");
    }

    private static Transport liveTransport() {
        Transport transport = mock(Transport.class);
        when(transport.isConnected()).thenReturn(true);
        return transport;
    }

    private MimeMessage message() throws MessagingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);
        helper.setFrom("someone@example.test");
        helper.setTo("recipient@example.test");
        helper.setSubject("Account Verification");
        helper.setText("<p>123456</p>", true);
        return message;
    }

    @Test
    @DisplayName("three messages share one login")
    void oneConnectionServesEverySend() throws Exception {
        Transport transport = liveTransport();
        sender.willOpen(transport);

        sender.send(message());
        sender.send(message());
        sender.send(message());

        assertThat(sender.opened).hasSize(1);
        verify(transport, times(3)).sendMessage(any(MimeMessage.class), any(Address[].class));
        verify(transport, never()).close();
    }

    @Test
    @DisplayName("the connection is opened at startup, before anyone registers")
    void startupOpensTheConnection() {
        Transport transport = liveTransport();
        sender.willOpen(transport);

        sender.openOnStartup();

        assertThat(sender.opened).containsExactly(transport);
    }

    @Test
    @DisplayName("a mail server that is down at startup is logged, not thrown - the next message tries again")
    void startupFailureIsNotFatal() {
        sender.willFailToOpen(new MessagingException("connection refused"));

        sender.openOnStartup();

        assertThat(sender.opened).isEmpty();
    }

    @Test
    @DisplayName("with no credentials configured nothing is opened at all")
    void withoutCredentialsNothingIsOpened() {
        sender.setUsername("");

        sender.openOnStartup();
        sender.keepAlive();

        assertThat(sender.opened).isEmpty();
    }

    @Test
    @DisplayName("a connection the server retired while idle is replaced on the next send")
    void aDeadConnectionIsReplaced() throws Exception {
        Transport dead = mock(Transport.class);
        when(dead.isConnected()).thenReturn(false);
        Transport fresh = liveTransport();
        sender.willOpen(dead, fresh);

        sender.openOnStartup();
        sender.send(message());

        assertThat(sender.opened).containsExactly(dead, fresh);
        verify(dead).close();
        verify(dead, never()).sendMessage(any(MimeMessage.class), any(Address[].class));
        verify(fresh).sendMessage(any(MimeMessage.class), any(Address[].class));
    }

    @Test
    @DisplayName("the keep-alive reopens a connection that has gone away, so a signup never waits for one")
    void keepAliveReopensADeadConnection() throws Exception {
        Transport dead = mock(Transport.class);
        when(dead.isConnected()).thenReturn(false);
        Transport fresh = liveTransport();
        sender.willOpen(dead, fresh);

        sender.openOnStartup();
        sender.keepAlive();

        assertThat(sender.opened).containsExactly(dead, fresh);
        verify(dead).close();
    }

    @Test
    @DisplayName("a connection that dies mid-send is reopened and the message retried once")
    void aSendOnADroppedConnectionIsRetried() throws Exception {
        Transport dropped = mock(Transport.class);
        // Alive when the send starts, gone by the time the write fails.
        when(dropped.isConnected()).thenReturn(true, false);
        doThrow(new MessagingException("connection reset"))
                .when(dropped).sendMessage(any(MimeMessage.class), any(Address[].class));
        Transport fresh = liveTransport();
        sender.willOpen(dropped, fresh);

        sender.openOnStartup();
        sender.send(message());

        assertThat(sender.opened).containsExactly(dropped, fresh);
        verify(fresh).sendMessage(any(MimeMessage.class), any(Address[].class));
    }

    @Test
    @DisplayName("a message the server rejects is reported, not sent a second time")
    void aRejectedMessageIsNotRetried() throws Exception {
        Transport transport = liveTransport();
        doThrow(new SendFailedException("550 no such recipient"))
                .when(transport).sendMessage(any(MimeMessage.class), any(Address[].class));
        sender.willOpen(transport);

        MimeMessage rejected = message();
        assertThatThrownBy(() -> sender.send(rejected)).isInstanceOf(MailSendException.class);

        assertThat(sender.opened).containsExactly(transport);
        verify(transport, times(1)).sendMessage(any(MimeMessage.class), any(Address[].class));
    }

    @Test
    @DisplayName("shutdown closes the connection rather than leaving it on the server")
    void shutdownClosesTheConnection() throws Exception {
        Transport transport = liveTransport();
        sender.willOpen(transport);

        sender.openOnStartup();
        sender.closeConnection();

        verify(transport).close();
    }
}
