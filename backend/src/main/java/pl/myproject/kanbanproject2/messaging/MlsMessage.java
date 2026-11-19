package pl.myproject.kanbanproject2.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;

@Entity
@Table(name = "mls_messages",
        uniqueConstraints = @UniqueConstraint(name = "uq_mls_messages_seq", columnNames = {"conversation_id", "seq"}))
public class MlsMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "epoch", nullable = false)
    private long epoch;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private MlsMessageKind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_device_id")
    private CryptoDevice senderDevice;

    @Column(name = "body", nullable = false)
    private byte[] body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected MlsMessage() {
    }

    public MlsMessage(Conversation conversation, long seq, long epoch, MlsMessageKind kind, CryptoDevice senderDevice,
                      byte[] body, Instant createdAt, Instant expiresAt) {
        this.conversation = conversation;
        this.seq = seq;
        this.epoch = epoch;
        this.kind = kind;
        this.sender = senderDevice.getUser();
        this.senderDevice = senderDevice;
        this.body = body.clone();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public long getSeq() {
        return seq;
    }

    public long getEpoch() {
        return epoch;
    }

    public MlsMessageKind getKind() {
        return kind;
    }

    public User getSender() {
        return sender;
    }

    public CryptoDevice getSenderDevice() {
        return senderDevice;
    }

    public byte[] getBody() {
        return body.clone();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
