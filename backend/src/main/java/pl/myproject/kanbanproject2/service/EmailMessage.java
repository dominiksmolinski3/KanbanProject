package pl.myproject.kanbanproject2.service;

/**
 * One message, composed and ready to post: a recipient, a subject, and the same content twice.
 *
 * <p>The two bodies are the point of the record existing at all. Every message this application
 * sends used to be an HTML string built inline at the call site, which meant a client that will
 * not render HTML - a screen reader working from the plain part, a text-only mail client, a spam
 * filter comparing the two - saw either nothing or the markup. A multipart message with an
 * {@code text/plain} alternative is the ordinary shape for transactional mail, and it is a field
 * on the provider's message rather than anything this application has to assemble.
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
