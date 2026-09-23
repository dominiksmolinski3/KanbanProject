package pl.myproject.kanbanproject2.service;

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
