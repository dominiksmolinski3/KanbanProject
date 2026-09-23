package pl.myproject.kanbanproject2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailSender emailSender;

    public void sendVerificationCode(String to, String code, long expiresInMinutes, Locale locale) {
        emailSender.send(MailTemplates.verification(to, code, expiresInMinutes, locale));
    }

    public void sendPasswordResetCode(String to, String code, long expiresInMinutes, Locale locale) {
        emailSender.send(MailTemplates.passwordReset(to, code, expiresInMinutes, locale));
    }

    public void sendTaskOverdue(String to, String taskTitle, String boardName,
                                LocalDateTime deadline, Locale locale) {
        emailSender.send(MailTemplates.taskOverdue(to, taskTitle, boardName, deadline, locale));
    }

    public void sendBoardInvitation(String to, String boardName, String inviterName,
                                    boolean registered, Locale locale) {
        emailSender.send(MailTemplates.boardInvitation(to, boardName, inviterName, registered, locale));
    }
}
