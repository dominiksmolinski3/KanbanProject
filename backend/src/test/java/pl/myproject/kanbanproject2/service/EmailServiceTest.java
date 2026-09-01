package pl.myproject.kanbanproject2.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Two things are worth pinning here, and neither is that mail gets sent.
 *
 * <p>The first is that {@code sendVerificationEmail} and {@code sendEmail} really are the same
 * call - the name is the only difference, and a reader who assumes otherwise will look for a
 * template that does not exist. The second is that a refusal from the transport reaches the caller:
 * {@code AuthenticationService} and {@code PasswordResetService} both turn it into {@code
 * EMAIL_SEND_FAILED}, which they can only do if it gets past this class.
 */
class EmailServiceTest {

    private final EmailSender sender = mock(EmailSender.class);
    private final EmailService service = new EmailService(sender);

    @Test
    @DisplayName("a verification mail is an ordinary mail - same recipient, subject and body, one transport call")
    void verificationMailIsJustAnHtmlMail() {
        service.sendVerificationEmail("someone@example.test", "Account Verification", "<p>123456</p>");

        verify(sender).send("someone@example.test", "Account Verification", "<p>123456</p>");
    }

    @Test
    @DisplayName("an html mail goes to the transport unchanged")
    void htmlMailIsPassedThrough() {
        service.sendEmail("someone@example.test", "Task overdue", "<p>hurry up</p>");

        verify(sender).send("someone@example.test", "Task overdue", "<p>hurry up</p>");
    }

    @Test
    @DisplayName("a transport that refuses the message is not swallowed on the way out")
    void aRefusalReachesTheCaller() {
        EmailDeliveryException refusal = new EmailDeliveryException("refused", new RuntimeException());
        doThrow(refusal).when(sender).send("someone@example.test", "Account Verification", "<p>123456</p>");

        assertThatThrownBy(() ->
                service.sendVerificationEmail("someone@example.test", "Account Verification", "<p>123456</p>"))
                .isSameAs(refusal);
    }
}
