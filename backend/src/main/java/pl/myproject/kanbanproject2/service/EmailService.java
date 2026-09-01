package pl.myproject.kanbanproject2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The application's view of sending mail: a recipient, a subject and an HTML body.
 *
 * <p>Which provider carries it is {@link EmailSender}'s business. This used to hold a {@code
 * JavaMailSender} and build a {@code MimeMessage} itself, which is why swapping SMTP for the Azure
 * Communication Services API touched every caller's imports; it no longer does.
 */
@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailSender emailSender;

    public void sendVerificationEmail(String to, String subject, String text) {
        sendEmail(to, subject, text);
    }

    public void sendEmail(String to, String subject, String htmlBody) {
        emailSender.send(to, subject, htmlBody);
    }
}
