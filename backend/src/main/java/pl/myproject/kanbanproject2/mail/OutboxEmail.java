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
 * One message waiting to be posted, as a row. The row exists so "the account was created" and
 * "somebody will be told the code" are one decision, not two - a row in the same database, written
 * in the same transaction, either happens with the account or not at all. A Service Bus queue
 * wouldn't fix that: it's a second system too, and an enqueue after the commit is lost if the
 * process dies in between.
 *
 * <p>The message is stored composed, both bodies, exactly as {@link EmailMessage} carries it -
 * re-running the template at send time would mail a wording change (or a template bug) to somebody
 * queued before it.
 *
 * <p><b>{@code SENT} means accepted</b>, and since {@code V16} {@link #providerMessageId} - Azure's
 * own id, written on acceptance - is what a later delivery report names; without it a report has
 * nothing to attach to.
 *
 * <p><b>These rows hold live credentials</b> (a pending verification or reset code is currently
 * redeemable), which is why nothing here is logged with its body and the relay's error path stores
 * the provider's complaint rather than the message. Rows are not swept - retention is a decision
 * nobody has made yet.
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
     * When the relay may next pick this up. Set to the creation instant so the first pass takes it,
     * and pushed out on each refusal - keeping the backoff in the row rather than the relay lets one
     * slow recipient wait without holding up the batch, and lets a restart resume where it left off.
     */
    @jakarta.persistence.Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @jakarta.persistence.Column(name = "sent_at")
    private Instant sentAt;

    /** Truncated: this is a note for whoever is reading the table, not the provider's stack trace. */
    @jakarta.persistence.Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * Azure's own id for the message, written on acceptance. This is the join key that lets a
     * delivery report say something about a <em>message</em> rather than an address. Null is
     * ordinary: a {@code DROPPED} row was never given to a provider, and an id that couldn't be
     * read back is still a send.
     */
    @jakarta.persistence.Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    /**
     * The provider's own word for what became of it - {@code Delivered}, {@code Bounced} and the
     * rest - stored verbatim rather than mapped onto an enum of this application's invention, so a
     * status nobody here has seen before is still storable rather than thrown away.
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

    /**
     * Takes the row out of the queue for the length of one lease, so nothing else posts it.
     *
     * <p><b>The claim is a status, not a held lock.</b> {@code FOR UPDATE SKIP LOCKED} makes two
     * relays selecting at once take disjoint rows, but the lock lives only as long as its
     * transaction, which cannot last as long as the send - so the status ({@code SENDING}) keeps the
     * partition after the commit, invisible to the claim query's {@code PENDING} filter.
     *
     * <p><b>The lease lives in {@code next_attempt_at}</b>, which already means "when the relay may
     * next take this row". A relay that dies between claim and answer would otherwise leave a
     * message silently never sent; a lease turns that into a message sent late instead, and it's not
     * a timeout on one send - it must outlast a whole batch, since every row is claimed at once and
     * the last is posted last.
     */
    void claimed(Instant now, java.time.Duration lease) {
        this.status = OutboxStatus.SENDING;
        this.nextAttemptAt = now.plus(lease);
    }

    /**
     * A claim that lapsed: the relay holding this row never came back. Put back in the queue rather
     * than failed - between a possible duplicate send and a possible lost one, this application
     * chooses the duplicate. The attempt is still charged, which bounds the other case: a message
     * whose own content kills the relay would otherwise be claimed and abandoned forever; five
     * lapsed claims make it a dead letter like any other refusal.
     */
    void abandoned(Instant now) {
        this.attempts++;
        this.lastError = "a relay claimed this row and did not come back; the claim lapsed";
        if (attempts >= MAX_ATTEMPTS) {
            this.status = OutboxStatus.FAILED;
            return;
        }
        this.status = OutboxStatus.PENDING;
        this.nextAttemptAt = now;
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
     * <p><b>Later reports win, on the provider's clock rather than ours.</b> One message can produce
     * several reports, and a retry can overtake the thing it's retrying; taking whichever arrived
     * last would let an {@code OutForDelivery} land on top of a {@code Delivered}. Comparing the
     * reported instants makes arrival order irrelevant.
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
     * A refusal: try again later, unless there have been enough of them. The delay doubles so a
     * provider having a bad minute costs one, not a hammering. The ceiling is an attempt count
     * rather than an age, since a verification code has expired long before five tries anyway -
     * {@code FAILED} is a record of what happened, not something still being waited on.
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
