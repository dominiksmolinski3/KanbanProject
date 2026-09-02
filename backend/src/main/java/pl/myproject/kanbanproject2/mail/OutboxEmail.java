package pl.myproject.kanbanproject2.mail;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import pl.myproject.kanbanproject2.service.EmailMessage;

import java.time.Instant;

/**
 * One message waiting to be posted, as a row.
 *
 * <p>The row exists so that "the account was created" and "somebody will be told the code" are one
 * decision rather than two. They used to be two: {@code signup} wrote a user and made an HTTPS call
 * to Azure, in that order or the other one depending on the route, and either half could happen
 * without the other. Writing the message to a queue instead does not fix that - a queue is a second
 * system too, and an enqueue after the commit is lost if the process dies in between. A row in the
 * same database, written in the same transaction, either happens with the account or does not
 * happen at all. That is the whole of the outbox pattern, and it is a table rather than a library.
 *
 * <p>The message is stored composed, both bodies, exactly as {@link EmailMessage} carries it. The
 * alternative - storing the facts and re-running the template at send time - would mean a message
 * queued before a wording change goes out with the new wording, and a message queued before a
 * template <em>bug</em> cannot be replayed as it was meant to read. What is queued is what was
 * composed.
 *
 * <p><b>These rows hold live credentials.</b> A pending verification or reset row carries a code
 * that is currently redeemable, which is why nothing here is logged with its body and why the
 * relay's error path stores the provider's complaint rather than the message. The rows are not
 * swept: a {@code SENT} row is a delivery record worth keeping, and a retention policy is a
 * decision nobody has made yet rather than something to guess at here.
 */
@Entity
@Table(name = "email_outbox")
public class OutboxEmail {

    /** As many attempts as the relay makes before it gives up on a row. */
    static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.Column(name = "recipient", nullable = false, length = 320)
    private String recipient;

    @jakarta.persistence.Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @jakarta.persistence.Column(name = "html_body", nullable = false, columnDefinition = "text")
    private String htmlBody;

    @jakarta.persistence.Column(name = "text_body", nullable = false, columnDefinition = "text")
    private String textBody;

    @Enumerated(EnumType.STRING)
    @jakarta.persistence.Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @jakarta.persistence.Column(name = "attempts", nullable = false)
    private int attempts;

    @jakarta.persistence.Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * When the relay may next pick this up.
     *
     * <p>Set to the creation instant so the first pass takes it, and pushed out on each refusal.
     * Keeping the backoff in the row rather than in the relay is what lets one slow recipient wait
     * without holding up the rest of the batch, and what lets a restart resume where it left off.
     */
    @jakarta.persistence.Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @jakarta.persistence.Column(name = "sent_at")
    private Instant sentAt;

    /** Truncated: this is a note for whoever is reading the table, not the provider's stack trace. */
    @jakarta.persistence.Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEmail() {
    }

    private OutboxEmail(EmailMessage message, Instant now) {
        this.recipient = message.to();
        this.subject = message.subject();
        this.htmlBody = message.htmlBody();
        this.textBody = message.textBody();
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    static OutboxEmail queueing(EmailMessage message, Instant now) {
        return new OutboxEmail(message, now);
    }

    EmailMessage asMessage() {
        return new EmailMessage(recipient, subject, htmlBody, textBody);
    }

    void accepted(Instant now) {
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
        this.attempts++;
        this.lastError = null;
    }

    void dropped(Instant now) {
        this.status = OutboxStatus.DROPPED;
        this.sentAt = now;
        this.lastError = "no mail account was configured when the relay reached this row";
    }

    /**
     * A refusal: try again later, unless there have been enough of them.
     *
     * <p>The delay doubles from the retry interval, which means a provider having a bad minute
     * costs one, and a provider having a bad afternoon is not hammered through it. The ceiling is
     * an attempt count rather than an age, because a message nobody can deliver after five tries is
     * not going to become deliverable, and a verification code has expired long before then anyway
     * - {@code FAILED} is a record of what happened, not a thing still being waited on.
     */
    void refused(String reason, Instant now, java.time.Duration firstBackoff) {
        this.attempts++;
        this.lastError = truncate(reason);
        if (attempts >= MAX_ATTEMPTS) {
            this.status = OutboxStatus.FAILED;
            return;
        }
        this.nextAttemptAt = now.plus(firstBackoff.multipliedBy(1L << (attempts - 1)));
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "the provider refused the message without saying why";
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    public Long getId() {
        return id;
    }

    public String getRecipient() {
        return recipient;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getLastError() {
        return lastError;
    }
}
