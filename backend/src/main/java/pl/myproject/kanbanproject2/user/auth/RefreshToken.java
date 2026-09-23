package pl.myproject.kanbanproject2.user.auth;

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

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @jakarta.persistence.Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @jakarta.persistence.Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @jakarta.persistence.Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @jakarta.persistence.Column(name = "chain_started_at", nullable = false)
    private Instant chainStartedAt;

    @jakarta.persistence.Column(name = "ip_address", length = 45)
    private String ipAddress;

    @jakarta.persistence.Column(name = "user_agent", length = 255)
    private String userAgent;

    @jakarta.persistence.Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
    }

    public RefreshToken(String tokenHash, User user, Instant issuedAt, Instant expiresAt,
                        Instant absoluteExpiresAt, Instant chainStartedAt, DeviceContext device) {
        this.tokenHash = tokenHash;
        this.user = user;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.absoluteExpiresAt = absoluteExpiresAt;
        this.chainStartedAt = chainStartedAt;
        this.ipAddress = device.ipAddress();
        this.userAgent = device.userAgent();
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public User getUser() {
        return user;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAbsoluteExpiresAt() {
        return absoluteExpiresAt;
    }

    public Instant getChainStartedAt() {
        return chainStartedAt;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Long getId() {
        return id;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void revokeAt(Instant when) {
        this.revokedAt = when;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant when) {
        return !expiresAt.isAfter(when);
    }
}
