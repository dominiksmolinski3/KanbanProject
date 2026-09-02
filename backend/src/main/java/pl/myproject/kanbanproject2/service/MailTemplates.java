package pl.myproject.kanbanproject2.service;

/**
 * The three messages this application sends, in one place and in two formats each.
 *
 * <p>They used to be three near-identical blocks of inline HTML - one in {@code
 * AuthenticationService}, one in {@code PasswordResetService}, one in {@code DeadlineNotifier} -
 * each with its own copy of the grey panel, the white card and the blue code, and each drifting
 * from the others by a shade of grey and a font size. Two of the three interpolated a value
 * without escaping it, which was harmless only because both values were six digits. The third
 * escaped three characters by hand.
 *
 * <p>So the layout is written once and the messages describe themselves: a heading, some
 * paragraphs, and optionally a code to read out. Everything interpolated goes through {@link
 * #escape}, including the digits, because a rule that is applied to the dangerous values only is a
 * rule somebody has to remember.
 *
 * <p>Not addressed here, and worth naming rather than leaving to be discovered: <b>these are
 * English</b>. The application speaks nine languages through {@code t()} on the client and mails in
 * one, and fixing that needs a locale stored against the account - the messages have no request to
 * read {@code Accept-Language} from, and the deadline sweep has no request at all. That is a
 * schema change and a product decision, not a template change.
 */
final class MailTemplates {

    private MailTemplates() {
    }

    static EmailMessage verification(String to, String code, long expiresInMinutes) {
        String intro = "Please enter the verification code below to continue. "
                + "It expires in " + expiresInMinutes + " minutes.";
        return new EmailMessage(to, "Account Verification",
                html("Welcome to our app!", intro, "Verification Code:", code, null),
                text("Welcome to our app!", intro, "Verification code: " + code, null));
    }

    static EmailMessage passwordReset(String to, String code, long expiresInMinutes) {
        String intro = "Enter the code below to choose a new password. "
                + "It expires in " + expiresInMinutes + " minutes.";
        String footnote = "If you did not ask for this, nothing has changed on your account "
                + "and you can ignore this message.";
        return new EmailMessage(to, "Password Reset",
                html("Reset your password", intro, "Reset Code:", code, footnote),
                text("Reset your password", intro, "Reset code: " + code, footnote));
    }

    static EmailMessage taskOverdue(String to, String taskTitle, String boardName, String deadline) {
        String intro = taskTitle + " on " + boardName + " has passed " + deadline + ".";
        String footnote = "Open the board to reschedule it or move it on.";
        return new EmailMessage(to, "Task overdue: " + taskTitle,
                html("A task has passed its deadline", intro, null, null, footnote),
                text("A task has passed its deadline", intro, null, footnote));
    }

    /**
     * The wrapper the three messages used to carry a copy of each: a grey page, a heading, a
     * paragraph, an optional white card holding a code, and an optional smaller footnote.
     */
    private static String html(String heading, String intro, String codeLabel, String code, String footnote) {
        StringBuilder body = new StringBuilder()
                .append("<html><body style=\"font-family: Arial, sans-serif;\">")
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

    /**
     * Everything interpolated into the HTML goes through this, including values that cannot
     * currently contain markup.
     *
     * <p>{@code DeadlineNotifier} escaped a task title for {@code &}, {@code <} and {@code >} and
     * stopped there, which is right for text between tags and wrong the moment a value lands in an
     * attribute. None does today. Quoting them anyway costs two more replacements and removes the
     * question.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
