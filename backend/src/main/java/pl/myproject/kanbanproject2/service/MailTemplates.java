package pl.myproject.kanbanproject2.service;

import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

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

    private static String on(LocalDateTime deadline, Locale locale) {
        return DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale)
                .format(deadline);
    }

    private static String say(Locale locale, String key, Object... arguments) {
        return MESSAGES.getMessage(key, arguments.length == 0 ? null : arguments, locale);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("mail/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
