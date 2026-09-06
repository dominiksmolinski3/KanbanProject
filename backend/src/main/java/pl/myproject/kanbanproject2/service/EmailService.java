package pl.myproject.kanbanproject2.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * The application's view of sending mail: name the message and the facts it needs.
 *
 * <p>Callers used to hand this a subject and a body they had assembled themselves, which is why
 * three services each carried their own copy of the same HTML. They now name the message -
 * "verification code", "reset code", "task overdue", "board invitation" - and the wording lives in {@link
 * MailTemplates}. What the caller keeps is the decision to send and what to do when it fails;
 * what it loses is a paragraph of markup it had no reason to own.
 *
 * <p>Every method takes the recipient's {@link Locale}, which is the one fact a caller has and
 * this cannot look up. It comes from the account rather than from a request header, so
 * {@code DeadlineNotifier} - which runs on a scheduler and has no request at all - can pass one
 * exactly as the two auth routes do.
 *
 * <p>Which provider carries it is {@link EmailSender}'s business, and that seam is unchanged: this
 * still composes and hands over exactly once.
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
     * Tells somebody they have been invited to a board.
     *
     * <p>The only message here whose recipient may have no account, which is what
     * {@code registered} says and the only thing it is used for. The locale therefore comes from
     * whichever account there is to read one from - theirs when they have signed up, the
     * inviter's when they have not - and that choice is the caller's, as it is for every other
     * method on this class.
     */
    public void sendBoardInvitation(String to, String boardName, String inviterName,
                                    boolean registered, Locale locale) {
        emailSender.send(MailTemplates.boardInvitation(to, boardName, inviterName, registered, locale));
    }
}
