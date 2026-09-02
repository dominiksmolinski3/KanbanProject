package pl.myproject.kanbanproject2.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import pl.myproject.kanbanproject2.user.SupportedLocales;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What is worth pinning about a template is not its wording.
 *
 * <p>Four things are. That both bodies exist and say the same thing, because the plain part is the
 * one nobody looks at and so the one that silently goes missing. That the code a person has to read
 * out is in both of them, because a message whose alternative part drops the code is worse than one
 * with no alternative part. That a value which arrives from a user - a task title, a board name -
 * cannot close a tag, which is the one way these messages could be made to carry something they did
 * not compose. And, new with the bundles, that <em>every</em> message renders in <em>every</em>
 * locale: nine languages times three messages is where a missing key or a mis-quoted apostrophe
 * lives, and neither is visible to the compiler.
 */
class MailTemplatesTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 1, 1, 9, 0);

    @Nested
    @DisplayName("in English, which is the base bundle")
    class InEnglish {

        @Test
        @DisplayName("a verification mail carries the code, the expiry and both bodies")
        void verificationCarriesTheCodeInBothBodies() {
            EmailMessage message = MailTemplates.verification("someone@example.test", "123456", 15, EN);

            assertThat(message.to()).isEqualTo("someone@example.test");
            assertThat(message.subject()).isEqualTo("Account Verification");
            assertThat(message.htmlBody()).contains("123456").contains("15 minutes").startsWith("<html ");
            assertThat(message.textBody()).contains("123456").contains("15 minutes").doesNotContain("<");
        }

        @Test
        @DisplayName("a reset mail says what to do if it was not asked for, in both bodies")
        void resetCarriesTheDisclaimer() {
            EmailMessage message = MailTemplates.passwordReset("someone@example.test", "654321", 10, EN);

            assertThat(message.subject()).isEqualTo("Password Reset");
            assertThat(message.htmlBody()).contains("654321").contains("did not ask for this");
            assertThat(message.textBody()).contains("654321").contains("did not ask for this").doesNotContain("<");
        }

        @Test
        @DisplayName("an overdue mail names the task in the subject and the board in the body")
        void overdueNamesTheTaskAndBoard() {
            EmailMessage message = MailTemplates.taskOverdue(
                    "someone@example.test", "Ship the release", "Delivery", DEADLINE, EN);

            assertThat(message.subject()).isEqualTo("Task overdue: Ship the release");
            assertThat(message.htmlBody()).contains("Ship the release").contains("Delivery").contains("2026");
            assertThat(message.textBody()).contains("Ship the release").contains("Delivery").doesNotContain("<");
        }

        @Test
        @DisplayName("a missing title, board and deadline are filled in by the bundle, not by the caller")
        void theStandInsComeFromTheBundle() {
            EmailMessage message = MailTemplates.taskOverdue("someone@example.test", " ", null, null, EN);

            assertThat(message.subject()).isEqualTo("Task overdue: Untitled task");
            assertThat(message.textBody()).contains("Untitled task").contains("your board");
        }

        @Test
        @DisplayName("an overdue mail has no code block, because there is no code to read out")
        void overdueHasNoCodeBlock() {
            EmailMessage message = MailTemplates.taskOverdue("someone@example.test", "T", "B", DEADLINE, EN);

            assertThat(message.htmlBody()).doesNotContain("box-shadow");
        }
    }

    @Nested
    @DisplayName("escaping, which is applied to everything rather than to the dangerous values")
    class Escaping {

        @Test
        @DisplayName("a task title that looks like markup is escaped in the html and left alone in the text")
        void aTitleCannotCloseATag() {
            String title = "<script>alert(" + '"' + "x" + '"' + ")</script>";
            EmailMessage message =
                    MailTemplates.taskOverdue("someone@example.test", title, "Q & A", DEADLINE, EN);

            assertThat(message.htmlBody())
                    .doesNotContain("<script>")
                    .contains("&lt;script&gt;")
                    .contains("&quot;")
                    .contains("Q &amp; A");
            // The plain part is not markup, so it needs no escaping - and escaping it there would
            // show the entities to the reader.
            assertThat(message.textBody()).contains(title).contains("Q & A");
        }

        @Test
        @DisplayName("an apostrophe in a board name is escaped rather than left to sit in an attribute one day")
        void anApostropheIsEscapedToo() {
            EmailMessage message =
                    MailTemplates.taskOverdue("someone@example.test", "T", "Anna's board", DEADLINE, EN);

            assertThat(message.htmlBody()).contains("Anna&#39;s board");
            assertThat(message.textBody()).contains("Anna's board");
        }

        @Test
        @DisplayName("the subject is not escaped, because a subject is not html")
        void theSubjectIsLeftAsItIs() {
            EmailMessage message =
                    MailTemplates.taskOverdue("someone@example.test", "Q & A", "B", DEADLINE, EN);

            assertThat(message.subject()).isEqualTo("Task overdue: Q & A");
        }
    }

    @Nested
    @DisplayName("in all nine languages")
    class InEveryLocale {

        static List<String> tags() {
            return List.copyOf(SupportedLocales.sorted());
        }

        /**
         * The one that earns its keep. Spring runs a message through {@code MessageFormat} only
         * when it is handed arguments, so a lone apostrophe in a translated string with a
         * {@code {0}} in it quotes the rest of the pattern and the placeholder survives into the
         * mail verbatim. Nothing else catches that: the bundle parses, the build passes, and one
         * language mails "{0}" at people.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("tags")
        @DisplayName("every message substitutes every argument")
        void everyArgumentIsSubstituted(String tag) {
            Locale locale = SupportedLocales.toLocale(tag);

            List<EmailMessage> messages = List.of(
                    MailTemplates.verification("someone@example.test", "123456", 15, locale),
                    MailTemplates.passwordReset("someone@example.test", "654321", 10, locale),
                    MailTemplates.taskOverdue("someone@example.test", "Ship it", "Delivery", DEADLINE, locale),
                    MailTemplates.taskOverdue("someone@example.test", null, null, null, locale));

            for (EmailMessage message : messages) {
                assertThat(message.subject())
                        .as("an unsubstituted placeholder in the %s subject", tag)
                        .doesNotContain("{");
                assertThat(message.textBody())
                        .as("an unsubstituted placeholder in the %s body", tag)
                        .doesNotContain("{");
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("tags")
        @DisplayName("every message still carries the code and the recipient's language")
        void everyMessageIsComplete(String tag) {
            Locale locale = SupportedLocales.toLocale(tag);

            EmailMessage verification = MailTemplates.verification("someone@example.test", "123456", 15, locale);

            assertThat(verification.htmlBody()).contains("123456").contains("lang=\"" + tag + "\"");
            assertThat(verification.textBody()).contains("123456");
            assertThat(verification.subject()).isNotBlank();
        }

        @Test
        @DisplayName("the messages actually differ, so nine bundles are not nine copies of English")
        void theBundlesAreNotAllEnglish() {
            List<String> subjects = tags().stream()
                    .map(tag -> MailTemplates
                            .verification("someone@example.test", "1", 15, SupportedLocales.toLocale(tag))
                            .subject())
                    .toList();

            assertThat(subjects).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("Arabic is marked right-to-left, and the other eight are not")
        void arabicReadsRightToLeft() {
            assertThat(MailTemplates
                    .verification("someone@example.test", "1", 15, Locale.forLanguageTag("ar"))
                    .htmlBody())
                    .contains("dir=\"rtl\"");
            assertThat(MailTemplates
                    .verification("someone@example.test", "1", 15, Locale.forLanguageTag("ja"))
                    .htmlBody())
                    .contains("dir=\"ltr\"");
        }

        /**
         * A language with no bundle falls back to the base file, which is English, rather than to
         * the JVM's own locale - which is what {@code fallbackToSystemLocale} being left on would
         * have meant, and which is a different answer on every host.
         */
        @Test
        @DisplayName("a language with no bundle falls back to English rather than to the server's locale")
        void anUnknownLanguageFallsBackToTheBaseBundle() {
            EmailMessage message = MailTemplates
                    .verification("someone@example.test", "123456", 15, Locale.forLanguageTag("is"));

            assertThat(message.subject()).isEqualTo("Account Verification");
        }
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
