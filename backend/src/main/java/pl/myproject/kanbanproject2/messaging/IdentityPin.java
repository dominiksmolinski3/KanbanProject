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
@Table(name = "identity_pins")
public class IdentityPin {

    @Id
    @Column(name = "user_id")
    private Integer userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "wrapped_private", nullable = false)
    private byte[] wrappedPrivate;

    @Column(name = "verifier", nullable = false)
    private byte[] verifier;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IdentityPin() {
    }

    public IdentityPin(User user, byte[] wrappedPrivate, byte[] verifier, Instant updatedAt) {
        this.user = user;
        replace(wrappedPrivate, verifier, updatedAt);
    }

    public void replace(byte[] wrappedPrivate, byte[] verifier, Instant updatedAt) {
        this.wrappedPrivate = wrappedPrivate.clone();
        this.verifier = verifier.clone();
        this.failedAttempts = 0;
        this.updatedAt = updatedAt;
    }

    public void unlocked() {
        this.failedAttempts = 0;
    }

    public Integer getUserId() {
        return userId;
    }

    public byte[] getWrappedPrivate() {
        return wrappedPrivate.clone();
    }

    public byte[] getVerifier() {
        return verifier.clone();
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
