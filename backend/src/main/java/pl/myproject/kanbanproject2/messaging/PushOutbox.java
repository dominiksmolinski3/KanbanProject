package pl.myproject.kanbanproject2.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

@Entity
@Table(name = "push_outbox")
public class PushOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "endpoint_id", nullable = false)
    private PushEndpoint endpoint;

    @Column(name = "conversation_id", nullable = false)
    private long conversationId;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PushOutbox() {
    }

    public PushOutbox(PushEndpoint endpoint, long conversationId, long seq, Instant now) {
        this.endpoint = endpoint;
        this.conversationId = conversationId;
        this.seq = seq;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public void leased(Instant now, Duration lease) {
        this.nextAttemptAt = now.plus(lease);
    }

    public void failed(Instant now, Duration backoff) {
        this.attempts++;
        this.nextAttemptAt = now.plus(backoff.multipliedBy(1L << Math.min(attempts - 1, 10)));
    }

    public Long getId() {
        return id;
    }

    public PushEndpoint getEndpoint() {
        return endpoint;
    }

    public long getConversationId() {
        return conversationId;
    }

    public long getSeq() {
        return seq;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
