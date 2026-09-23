package pl.myproject.kanbanproject2.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EmailServiceTest {
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 1, 1, 9, 0);

    private final EmailSender sender = mock(EmailSender.class);
    private final EmailService service = new EmailService(sender);

    private EmailMessage posted() {
        ArgumentCaptor<EmailMessage> message = ArgumentCaptor.forClass(EmailMessage.class);
        verify(sender).send(message.capture());
        return message.getValue();
    }

    @Test
    @DisplayName("a verification send composes the verification message and posts it once")
    void verificationIsComposedAndPostedOnce() {
        service.sendVerificationCode("someone@example.test", "123456", 15, Locale.FRENCH);

        assertThat(posted()).isEqualTo(MailTemplates.verification("someone@example.test", "123456", 15, Locale.FRENCH));
    }

    @Test
    @DisplayName("a reset send composes the reset message and posts it once")
    void resetIsComposedAndPostedOnce() {
        service.sendPasswordResetCode("someone@example.test", "654321", 10, Locale.FRENCH);

        assertThat(posted()).isEqualTo(MailTemplates.passwordReset("someone@example.test", "654321", 10, Locale.FRENCH));
    }

    @Test
    @DisplayName("an overdue send composes the overdue message and posts it once")
    void overdueIsComposedAndPostedOnce() {
        service.sendTaskOverdue("someone@example.test", "Ship it", "Delivery", DEADLINE, Locale.FRENCH);

        assertThat(posted())
                .isEqualTo(MailTemplates.taskOverdue("someone@example.test", "Ship it", "Delivery", DEADLINE, Locale.FRENCH));
    }

    @Test
    @DisplayName("a transport that refuses the message is not swallowed on the way out")
    void aRefusalReachesTheCaller() {
        EmailDeliveryException refusal = new EmailDeliveryException("refused", new RuntimeException());
        doThrow(refusal).when(sender).send(any());

        assertThatThrownBy(() -> service.sendVerificationCode("someone@example.test", "123456", 15, Locale.FRENCH))
                .isSameAs(refusal);
    }
}
