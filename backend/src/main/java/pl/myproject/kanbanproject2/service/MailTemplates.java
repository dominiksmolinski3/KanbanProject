package pl.myproject.kanbanproject2.service;

import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * The four messages this application sends, in one place, in two formats and in nine languages.
 * They used to be three near-identical blocks of inline HTML, each drifting from the others - so
 * the layout is written once and messages describe themselves: a heading, some paragraphs, and
 * optionally a code. Everything interpolated goes through {@link #escape}, including the digits,
 * since a rule applied only to dangerous values is one somebody has to remember.
 *
 * <p><b>The wording lives in {@code mail/messages*.properties}, not here.</b> The language comes
 * from a column on the account rather than {@code Accept-Language}, since the deadline sweep has no
 * request to read a header from and a browser-guessed language goes stale the moment somebody
 * travels.
 *
 * <p>The message source is static and built once - a fixed set of bundles rather than anything a
 * deployment configures. {@code fallbackToSystemLocale} is off, so the base
 * {@code messages.properties} (the English one; there is no {@code messages_en}) is the fallback
 * rather than whatever locale the JVM happens to run in.
 *
 * <p><b>One trap the compiler can't see:</b> Spring runs a message through {@code MessageFormat}
 * only when given arguments, so a lone apostrophe is harmless without a {@code {0}} and swallows the
 * rest of the pattern when there is one. {@code MailTemplatesTest} renders every message in every
 * locale and fails on a surviving brace.
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
     * The overdue notice, which takes facts rather than finished sentences. A missing title, an
     * unnamed board and a missing deadline were all phrased in English inside
     * {@code DeadlineNotifier}; once the message has a language, "your board" and the date format
     * are translations too - {@code d MMM yyyy} reads as a mistake in most of the other eight.
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
     * The invitation, the one message sent to somebody who may not be a user here. {@code
     * registered} decides only the last sentence: sign in, or sign up. Two intro keys rather than a
     * second paragraph, the same shape {@code mail.overdue.intro} already has.
     *
     * <p><b>There is no link and no token in it.</b> An invitation is redeemed by whoever holds the
     * account at the address, so a forwarded message gives nobody anything - and a link would need a
     * base URL this deployment does not configure anywhere.
     */
    static EmailMessage boardInvitation(String to, String boardName, String inviterName,
                                        boolean registered, Locale locale) {
        String board = blank(boardName) ? say(locale, "mail.overdue.defaultBoard") : boardName;
        String inviter = blank(inviterName) ? say(locale, "mail.invitation.someone") : inviterName;
        String heading = say(locale, "mail.invitation.heading");
        String footnote = say(locale, "mail.invitation.footnote");
        String intro = registered
                ? say(locale, "mail.invitation.introExisting", inviter, board)
                : say(locale, "mail.invitation.introNew", inviter, board);

        return new EmailMessage(to, say(locale, "mail.invitation.subject", board),
                html(locale, heading, intro, null, null, footnote),
                text(heading, intro, null, footnote));
    }

    /**
     * The wrapper the three messages used to each carry a copy of: a grey page, a heading, a
     * paragraph, an optional code card, and an optional footnote. {@code lang} and {@code dir} are
     * on the root element rather than left to the client's guess, since Arabic is one of the nine
     * and a right-to-left message rendered left-to-right puts punctuation at the wrong end of every
     * line.
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
        // rather than to whatever locale the JVM happens to have been started in.
        source.setFallbackToSystemLocale(false);
        return source;
    }

    /**
     * Everything interpolated into the HTML goes through this, including values that cannot
     * currently contain markup. {@code DeadlineNotifier} escaped only {@code &}, {@code <} and
     * {@code >} - right for text between tags, wrong once a value lands in an attribute, as
     * {@code lang} and {@code dir} now do.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
