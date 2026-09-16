package pl.myproject.kanbanproject2.service;

/**
 * One message, composed and ready to post: a recipient, a subject, and the same content twice. Two
 * bodies exist because messages used to be an HTML string built inline at the call site, leaving a
 * client that won't render HTML - a screen reader, a text-only client, a spam filter - seeing
 * nothing or markup; a multipart message with a {@code text/plain} alternative is the ordinary shape
 * for transactional mail.
 *
 * <p>Both bodies are required rather than the text one being optional, because an optional
 * alternative part is one that gets left out. {@link MailTemplates} is the only thing that builds
 * these, and it writes both or neither.
 */
public record EmailMessage(String to, String subject, String htmlBody, String textBody) {

    public EmailMessage {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("a message needs a recipient");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("a message needs a subject");
        }
        if (htmlBody == null || textBody == null) {
            throw new IllegalArgumentException("a message needs both an html and a plain-text body");
        }
    }
}
