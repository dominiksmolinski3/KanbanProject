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

import java.time.Instant;

@Entity
@Table(name = "mls_welcomes")
public class MlsWelcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private CryptoDevice device;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "body", nullable = false)
    private byte[] body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MlsWelcome() {
    }

    public MlsWelcome(CryptoDevice device, Conversation conversation, long seq, byte[] body, Instant createdAt) {
        this.device = device;
        this.conversation = conversation;
        this.seq = seq;
        this.body = body.clone();
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public CryptoDevice getDevice() {
        return device;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public long getSeq() {
        return seq;
    }

    public byte[] getBody() {
        return body.clone();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
