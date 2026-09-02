package pl.myproject.kanbanproject2.service;

import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * The three messages this application sends, in one place, in two formats and in nine languages.
 *
 * <p>They used to be three near-identical blocks of inline HTML - one in {@code
 * AuthenticationService}, one in {@code PasswordResetService}, one in {@code DeadlineNotifier} -
 * each with its own copy of the grey panel, the white card and the blue code, and each drifting
 * from the others by a shade of grey and a font size. So the layout is written once and the
 * messages describe themselves: a heading, some paragraphs, and optionally a code to read out.
 * Everything interpolated goes through {@link #escape}, including the digits, because a rule that
 * is applied to the dangerous values only is a rule somebody has to remember.
 *
 * <p><b>The wording lives in {@code mail/messages*.properties}, not here.</b> Putting the three
 * messages in one place is what made it obvious that the application speaks nine languages on
 * screen and mailed in exactly one - the gap was always there and was spread thinly enough across
 * three services to be invisible. The language comes from a column on the account rather than from
 * {@code Accept-Language}: the deadline sweep has no request to read a header from, and a
 * verification mail addressed from whichever browser happened to sign up is addressed from a guess
 * that goes stale the first time somebody travels.
 *
 * <p>The message source is static and built once. It is a fixed set of bundles on the classpath
 * rather than anything a deployment configures, so a bean would be a bean nothing else could ever
 * want a different one of. {@code fallbackToSystemLocale} is off, which is what makes the base
 * {@code messages.properties} - the English one, and the reason there is no {@code messages_en} -
 * the fallback rather than whatever language the server happens to be running in.
 *
 * <p>One trap worth naming, because the compiler cannot see it: Spring runs a message through
 * {@code MessageFormat} only when it is given arguments, so a lone apostrophe is harmless in a
 * message with no {@code {0}} and swallows the rest of the pattern in one that has them.
 * {@code MailTemplatesTest} renders every message in every locale and fails on a surviving brace,
 * which is what that mistake produces.
 */
final class MailTemplates {

    private static final MessageSource MESSAGES = messageSource();

    private MailTemplates() {
    }

    static EmailMessage verification(String to, String code, long expiresInMinutes, Locale locale) {
        String intro = say(locale, "mail.verification.intro", expiresInMinutes);
        String heading = say(locale, "mail.verification.heading");
        return new EmailMessage(to, say(locale, "mail.verification.subject"),
                html(locale, heading, intro, say(locale, "mail.verification.codeLabel"), code, null),
                text(heading, intro, say(locale, "mail.verification.codeText", code), null));
    }

    static EmailMessage passwordReset(String to, String code, long expiresInMinutes, Locale locale) {
        String intro = say(locale, "mail.reset.intro", expiresInMinutes);
        String heading = say(locale, "mail.reset.heading");
        String footnote = say(locale, "mail.reset.footnote");
        return new EmailMessage(to, say(locale, "mail.reset.subject"),
                html(locale, heading, intro, say(locale, "mail.reset.codeLabel"), code, footnote),
                text(heading, intro, say(locale, "mail.reset.codeText", code), footnote));
    }

    /**
     * The overdue notice, which is the one that takes facts rather than finished sentences.
     *
     * <p>A missing title, a task on a board with no name, and a task flagged overdue with no
     * deadline on it were all phrased in {@code DeadlineNotifier}, in English, before anything
     * here was reached. There is nowhere else those three can go once the message has a language:
     * "your board" is a translation, and so is the date - {@code d MMM yyyy} is an English
     * rendering of an instant and reads as a mistake in most of the other eight.
     */
    static EmailMessage taskOverdue(String to, String taskTitle, String boardName,
                                    LocalDateTime deadline, Locale locale) {
        String title = blank(taskTitle) ? say(locale, "mail.overdue.untitled") : taskTitle;
        String board = blank(boardName) ? say(locale, "mail.overdue.defaultBoard") : boardName;
        String heading = say(locale, "mail.overdue.heading");
        String footnote = say(locale, "mail.overdue.footnote");
        String intro = deadline == null
                ? say(locale, "mail.overdue.introNoDeadline", title, board)
                : say(locale, "mail.overdue.intro", title, board, on(deadline, locale));

        return new EmailMessage(to, say(locale, "mail.overdue.subject", title),
                html(locale, heading, intro, null, null, footnote),
                text(heading, intro, null, footnote));
    }

    /**
     * The wrapper the three messages used to carry a copy of each: a grey page, a heading, a
     * paragraph, an optional white card holding a code, and an optional smaller footnote.
     *
     * <p>{@code lang} and {@code dir} are on the root element rather than left to the client's
     * guess. Arabic is one of the nine, and a right-to-left message rendered left-to-right is not
     * a styling detail - it is the punctuation landing at the wrong end of every line.
     */
    private static String html(Locale locale, String heading, String intro,
                               String codeLabel, String code, String footnote) {
        StringBuilder body = new StringBuilder()
                .append("<html lang=\"").append(escape(locale.toLanguageTag()))
                .append("\" dir=\"").append(escape(say(locale, "mail.dir")))
                .append("\"><body style=\"font-family: Arial, sans-serif;\">")
                .append("<div style=\"background-color: #f5f5f5; padding: 20px;\">")
                .append("<h2 style=\"color: #333;\">").append(escape(heading)).append("</h2>")
                .append("<p style=\"font-size: 16px;\">").append(escape(intro)).append("</p>");
        if (code != null) {
            body.append("<div style=\"background-color: #fff; padding: 20px; border-radius: 5px; "
                            + "box-shadow: 0 0 10px rgba(0,0,0,0.1);\">")
                    .append("<h3 style=\"color: #333;\">").append(escape(codeLabel)).append("</h3>")
                    .append("<p style=\"font-size: 18px; font-weight: bold; color: #007bff;\">")
                    .append(escape(code)).append("</p>")
                    .append("</div>");
        }
        if (footnote != null) {
            body.append("<p style=\"font-size: 14px; color: #666;\">").append(escape(footnote)).append("</p>");
        }
        return body.append("</div></body></html>").toString();
    }

    /**
     * The same message for a client that will not render the other one. Not a stripped copy of the
     * markup - blank lines where the panels were, which is what the plain part is for.
     */
    private static String text(String heading, String intro, String code, String footnote) {
        StringBuilder body = new StringBuilder(heading).append("\n\n").append(intro);
        if (code != null) {
            body.append("\n\n").append(code);
        }
        if (footnote != null) {
            body.append("\n\n").append(footnote);
        }
        return body.append("\n").toString();
    }

    /** The deadline as the reader's language writes one. */
    private static String on(LocalDateTime deadline, Locale locale) {
        return DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale)
                .format(deadline);
    }

    private static String say(Locale locale, String key, Object... arguments) {
        // No default argument: a key this class asks for and no bundle defines is a bug in this
        // class, and the base bundle answers every one of them. NoSuchMessageException is louder
        // than a message that quietly mails somebody the name of a key.
        return MESSAGES.getMessage(key, arguments.length == 0 ? null : arguments, locale);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("mail/messages");
        source.setDefaultEncoding("UTF-8");
        // Off, so an account set to a language with no bundle falls back to the English base file
        // rather than to whatever locale the JVM happens to have been started in - which on one
        // host is English and on the next is a coin toss.
        source.setFallbackToSystemLocale(false);
        return source;
    }

    /**
     * Everything interpolated into the HTML goes through this, including values that cannot
     * currently contain markup.
     *
     * <p>{@code DeadlineNotifier} escaped a task title for {@code &}, {@code <} and {@code >} and
     * stopped there, which is right for text between tags and wrong the moment a value lands in an
     * attribute. Nothing did then; two things do now - {@code lang} and {@code dir} - which is the
     * second time this paragraph has turned out to be about something real.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
