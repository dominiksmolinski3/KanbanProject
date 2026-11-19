package pl.myproject.kanbanproject2.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import pl.myproject.kanbanproject2.user.User;

import java.time.Instant;

@Entity
@Table(name = "identity_keys")
public class IdentityKey {

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    @Column(name = "wrapped_private", nullable = false)
    private byte[] wrappedPrivate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdentityKey() {
    }

    public IdentityKey(User user, byte[] publicKey, byte[] wrappedPrivate, Instant createdAt) {
        this.user = user;
        this.publicKey = publicKey.clone();
        this.wrappedPrivate = wrappedPrivate.clone();
        this.createdAt = createdAt;
    }

    public void replace(byte[] publicKey, byte[] wrappedPrivate, Instant createdAt) {
        this.publicKey = publicKey.clone();
        this.wrappedPrivate = wrappedPrivate.clone();
        this.createdAt = createdAt;
    }

    public Integer getUserId() {
        return userId;
    }

    public byte[] getPublicKey() {
        return publicKey.clone();
    }

    public byte[] getWrappedPrivate() {
        return wrappedPrivate.clone();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
