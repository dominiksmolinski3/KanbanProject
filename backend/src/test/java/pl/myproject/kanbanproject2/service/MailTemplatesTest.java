package pl.myproject.kanbanproject2.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is worth pinning about a template is not its wording.
 *
 * <p>Three things are. That both bodies exist and say the same thing, because the plain part is
 * the one nobody looks at and so the one that silently goes missing. That the code a person has to
 * read out is in both of them, because a message whose alternative part drops the code is worse
 * than one with no alternative part. And that a value which arrives from a user - a task title, a
 * board name - cannot close a tag, which is the one way these messages could be made to carry
 * something they did not compose.
 */
class MailTemplatesTest {

    @Test
    @DisplayName("a verification mail carries the code, the expiry and both bodies")
    void verificationCarriesTheCodeInBothBodies() {
        EmailMessage message = MailTemplates.verification("someone@example.test", "123456", 15);

        assertThat(message.to()).isEqualTo("someone@example.test");
        assertThat(message.subject()).isEqualTo("Account Verification");
        assertThat(message.htmlBody()).contains("123456").contains("15 minutes").startsWith("<html>");
        assertThat(message.textBody()).contains("123456").contains("15 minutes").doesNotContain("<");
    }

    @Test
    @DisplayName("a reset mail says what to do if it was not asked for, in both bodies")
    void resetCarriesTheDisclaimer() {
        EmailMessage message = MailTemplates.passwordReset("someone@example.test", "654321", 10);

        assertThat(message.subject()).isEqualTo("Password Reset");
        assertThat(message.htmlBody()).contains("654321").contains("did not ask for this");
        assertThat(message.textBody()).contains("654321").contains("did not ask for this").doesNotContain("<");
    }

    @Test
    @DisplayName("an overdue mail names the task in the subject and the board in the body")
    void overdueNamesTheTaskAndBoard() {
        EmailMessage message = MailTemplates.taskOverdue(
                "someone@example.test", "Ship the release", "Delivery", "its deadline of 1 Jan 2026, 09:00");

        assertThat(message.subject()).isEqualTo("Task overdue: Ship the release");
        assertThat(message.htmlBody()).contains("Ship the release").contains("Delivery").contains("1 Jan 2026");
        assertThat(message.textBody()).contains("Ship the release").contains("Delivery").doesNotContain("<");
    }

    @Test
    @DisplayName("an overdue mail has no code block, because there is no code to read out")
    void overdueHasNoCodeBlock() {
        EmailMessage message = MailTemplates.taskOverdue("someone@example.test", "T", "B", "its deadline");

        assertThat(message.htmlBody()).doesNotContain("box-shadow");
    }

    @Test
    @DisplayName("a task title that looks like markup is escaped in the html and left alone in the text")
    void aTitleCannotCloseATag() {
        String title = "<script>alert(" + '"' + "x" + '"' + ")</script>";
        EmailMessage message =
                MailTemplates.taskOverdue("someone@example.test", title, "Q & A", "its deadline");

        assertThat(message.htmlBody())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;")
                .contains("&quot;")
                .contains("Q &amp; A");
        // The plain part is not markup, so it needs no escaping - and escaping it there would show
        // the entities to the reader.
        assertThat(message.textBody()).contains(title).contains("Q & A");
    }

    @Test
    @DisplayName("an apostrophe in a board name is escaped rather than left to sit in an attribute one day")
    void anApostropheIsEscapedToo() {
        EmailMessage message =
                MailTemplates.taskOverdue("someone@example.test", "T", "Anna's board", "its deadline");

        assertThat(message.htmlBody()).contains("Anna&#39;s board");
        assertThat(message.textBody()).contains("Anna's board");
    }

    @Test
    @DisplayName("the subject is not escaped, because a subject is not html")
    void theSubjectIsLeftAsItIs() {
        EmailMessage message = MailTemplates.taskOverdue("someone@example.test", "Q & A", "B", "its deadline");

        assertThat(message.subject()).isEqualTo("Task overdue: Q & A");
    }

    @Test
    @DisplayName("a message with no recipient, no subject or only one body is refused where it is built")
    void anIncompleteMessageIsRefused() {
        assertThatThrownBy(() -> new EmailMessage(" ", "Subject", "<p>x</p>", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recipient");
        assertThatThrownBy(() -> new EmailMessage("someone@example.test", null, "<p>x</p>", "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subject");
        assertThatThrownBy(() -> new EmailMessage("someone@example.test", "Subject", "<p>x</p>", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plain-text");
    }
}
