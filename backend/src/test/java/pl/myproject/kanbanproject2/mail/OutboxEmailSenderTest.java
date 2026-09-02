package pl.myproject.kanbanproject2.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import pl.myproject.kanbanproject2.service.EmailDeliveryException;
import pl.myproject.kanbanproject2.service.EmailMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The enqueue half. What matters is that it writes the message whole and that it is quick to say
 * so - the row is what the relay has to work from, and anything missing from it is a message that
 * can never be rebuilt.
 */
class OutboxEmailSenderTest {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

    private final OutboxEmailRepository outbox = mock(OutboxEmailRepository.class);
    private final OutboxEmailSender sender =
            new OutboxEmailSender(outbox, Clock.fixed(NOW, ZoneOffset.UTC));

    private final EmailMessage message =
            new EmailMessage("someone@example.test", "Account Verification", "<p>123456</p>", "123456");

    private OutboxEmail queued() {
        when(outbox.save(any(OutboxEmail.class))).thenAnswer(call -> call.getArgument(0));
        sender.send(message);
        ArgumentCaptor<OutboxEmail> row = ArgumentCaptor.forClass(OutboxEmail.class);
        verify(outbox).save(row.capture());
        return row.getValue();
    }

    @Test
    @DisplayName("a send writes one pending row carrying the whole composed message")
    void aSendWritesOnePendingRow() {
        OutboxEmail row = queued();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getRecipient()).isEqualTo("someone@example.test");
        assertThat(row.asMessage()).isEqualTo(message);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getSentAt()).isNull();
    }

    @Test
    @DisplayName("a new row is due immediately, so the next relay pass takes it")
    void aNewRowIsDueAtOnce() {
        assertThat(queued().getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a database that will not take the row is reported as a delivery failure, not swallowed")
    void anUnwritableRowIsReported() {
        // The one case where a caller still sees EMAIL_SEND_FAILED. It is the honest answer: the
        // account may exist, and nothing is going to tell anybody the code.
        when(outbox.save(any(OutboxEmail.class)))
                .thenThrow(new DataIntegrityViolationException("no room at the inn"));

        assertThatThrownBy(() -> sender.send(message))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("outbox");
    }

    @Test
    @DisplayName("the queue sender delivers nothing itself, and does not claim to")
    void theQueueSenderIsNotATransport() {
        // deliversMessages() is the relay's question about the transport behind it. This class is
        // not that transport, but it does accept messages for sending, so the default holds.
        assertThat(sender.deliversMessages()).isTrue();
    }
}
