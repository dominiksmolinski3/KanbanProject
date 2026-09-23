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

@Entity
@Table(name = "email_outbox")
public class OutboxEmail {

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

    @jakarta.persistence.Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @jakarta.persistence.Column(name = "sent_at")
    private Instant sentAt;

    @jakarta.persistence.Column(name = "last_error", length = 500)
    private String lastError;

    @jakarta.persistence.Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @jakarta.persistence.Column(name = "delivery_status", length = 32)
    private String deliveryStatus;

    @jakarta.persistence.Column(name = "delivery_reported_at")
    private Instant deliveryReportedAt;

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

    void claimed(Instant now, java.time.Duration lease) {
        this.status = OutboxStatus.SENDING;
        this.nextAttemptAt = now.plus(lease);
    }

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
