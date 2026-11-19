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
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "crypto_devices")
public class CryptoDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "session_chain", nullable = false)
    private UUID sessionChain;

    @Column(name = "signature_key", nullable = false)
    private byte[] signatureKey;

    @Column(name = "credential", nullable = false)
    private byte[] credential;

    @Column(name = "identity_signature", nullable = false)
    private byte[] identitySignature;

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected CryptoDevice() {
    }

    public CryptoDevice(User user, UUID sessionChain, byte[] signatureKey, byte[] credential,
                        byte[] identitySignature, String label, Instant createdAt) {
        this.user = user;
        this.sessionChain = sessionChain;
        this.signatureKey = signatureKey.clone();
        this.credential = credential.clone();
        this.identitySignature = identitySignature.clone();
        this.label = label;
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
    }

    public void attachTo(UUID chain, String label, Instant at) {
        this.sessionChain = chain;
        if (label != null) {
            this.label = label;
        }
        this.lastSeenAt = at;
    }

    public void seenAt(Instant at) {
        this.lastSeenAt = at;
    }

    public void revokeAt(Instant at) {
        this.revokedAt = at;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public UUID getSessionChain() {
        return sessionChain;
    }

    public byte[] getSignatureKey() {
        return signatureKey.clone();
    }

    public byte[] getCredential() {
        return credential.clone();
    }

    public byte[] getIdentitySignature() {
        return identitySignature.clone();
    }

    public String getLabel() {
        return label;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
