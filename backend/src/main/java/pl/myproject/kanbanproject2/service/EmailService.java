package pl.myproject.kanbanproject2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * The application's view of sending mail: name the message and the facts it needs. Callers used to
 * assemble their own HTML, which is why three services each carried a copy of the same markup; they
 * now name the message and the wording lives in {@link MailTemplates}.
 *
 * <p>Every method takes the recipient's {@link Locale}, from the account rather than a request
 * header, since {@code DeadlineNotifier} runs on a scheduler with no request to read a header from.
 * Which provider carries the message is {@link EmailSender}'s business; this still composes and
 * hands over exactly once.
 */
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

    /**
     * Tells somebody they have been invited to a board - the only message here whose recipient may
     * have no account, which is what {@code registered} decides. The locale comes from whichever
     * account there is to read one from: theirs if they've signed up, the inviter's if not.
     */
    public void sendBoardInvitation(String to, String boardName, String inviterName,
                                    boolean registered, Locale locale) {
        emailSender.send(MailTemplates.boardInvitation(to, boardName, inviterName, registered, locale));
    }
}
