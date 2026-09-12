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
 * <p><b>{@code SENT} means accepted, and since {@code V16} the row can say more than that.</b>
 * {@link #providerMessageId} is Azure's own id for the message, written when it is accepted, and it
 * is what a delivery report arriving later names. Without an id there is nothing to attach the
 * report to and "did the code reach them" stays unanswerable however much the provider knows.
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

    /**
     * Azure's own id for the message, written when the provider accepts it.
     *
     * <p>This is the join key and it is the whole reason a delivery report can say anything about
     * a <em>message</em> rather than about an address. Null is ordinary: a {@code DROPPED} row was
     * never given to a provider, a row queued before {@code V16} predates anybody listening, and a
     * send whose id could not be read back is still a send.
     */
    @jakarta.persistence.Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    /**
     * The provider's own word for what became of it - {@code Delivered}, {@code Bounced} and the
     * rest - stored verbatim rather than mapped onto an enum of this application's invention.
     *
     * <p>Two reasons. The person reading this column is diagnosing a mail that did not arrive, and
     * the provider's documentation and its own logs are written in the provider's vocabulary. And a
     * status nobody here has seen before has to be storable: a provider that adds one is not a
     * reason to throw the report away.
     */
    @jakarta.persistence.Column(name = "delivery_status", length = 32)
    private String deliveryStatus;

    /** When the provider says the attempt happened - not when this application heard about it. */
    @jakarta.persistence.Column(name = "delivery_reported_at")
    private Instant deliveryReportedAt;

    /** Whatever the provider said about it, when it says anything. Truncated like {@link #lastError}. */
    @jakarta.persistence.Column(name = "delivery_detail", length = 500)
    private String deliveryDetail;

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

    void accepted(Instant now, String providerMessageId) {
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
        this.attempts++;
        this.lastError = null;
        this.providerMessageId = providerMessageId;
    }

    /**
     * Records what the provider says became of this message.
     *
     * <p><b>Later reports win, and "later" is the provider's clock rather than ours.</b> Azure
     * reports per recipient and per attempt, so one message can produce several; they travel over
     * a network and a retry can overtake the thing it is retrying. Taking whichever arrived last
     * would let an {@code OutForDelivery} land on top of a {@code Delivered} and leave the row
     * saying something that was true a second earlier and is not true now. Comparing the reported
     * instants makes the order of arrival irrelevant, which is the only ordering promise worth
     * making about a webhook.
     *
     * @return whether this report was the newer one and was kept.
     */
    boolean deliveryReported(String status, String detail, Instant reportedAt) {
        if (deliveryReportedAt != null && reportedAt.isBefore(deliveryReportedAt)) {
            return false;
        }
        this.deliveryStatus = truncate(status, 32);
        this.deliveryDetail = truncate(detail, 500);
        this.deliveryReportedAt = reportedAt;
        return true;
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
        return truncate(reason, 500);
    }

    /** Null stays null here: an absent detail is a fact, unlike an absent refusal reason. */
    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
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

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public Instant getDeliveryReportedAt() {
        return deliveryReportedAt;
    }

    public String getDeliveryDetail() {
        return deliveryDetail;
    }
}
