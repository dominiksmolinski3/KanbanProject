package pl.myproject.kanbanproject2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The application's view of sending mail: name the message and the facts it needs.
 *
 * <p>Callers used to hand this a subject and a body they had assembled themselves, which is why
 * three services each carried their own copy of the same HTML. They now name the message -
 * "verification code", "reset code", "task overdue" - and the wording lives in {@link
 * MailTemplates}. What the caller keeps is the decision to send and what to do when it fails;
 * what it loses is a paragraph of markup it had no reason to own.
 *
 * <p>Which provider carries it is {@link EmailSender}'s business, and that seam is unchanged: this
 * still composes and hands over exactly once.
 */
@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailSender emailSender;

    public void sendVerificationCode(String to, String code, long expiresInMinutes) {
        emailSender.send(MailTemplates.verification(to, code, expiresInMinutes));
    }

    public void sendPasswordResetCode(String to, String code, long expiresInMinutes) {
        emailSender.send(MailTemplates.passwordReset(to, code, expiresInMinutes));
    }

    public void sendTaskOverdue(String to, String taskTitle, String boardName, String deadline) {
        emailSender.send(MailTemplates.taskOverdue(to, taskTitle, boardName, deadline));
    }
}
