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

/**
 * One issued refresh token, as a row that can be withdrawn — unlike an access token, a signed claim
 * that only its own expiry can end, a refresh token is a row that the server can delete, which is
 * what makes a session something it can end.
 *
 * <p>Only a SHA-256 digest is stored, never the token, for the same reason {@code password_reset_code}
 * is hashed: the table is exactly what an attacker with read access has. Not password hashing and
 * no slow KDF, since the input is 256 bits from {@link java.security.SecureRandom} with no
 * dictionary to run.
 *
 * <p>Rows are kept rather than deleted on revocation, since {@code revokedAt} being set is what
 * makes replay of an already-rotated token detectable — turning a stolen token into a signal
 * instead of a silent second session.
 *
 * <p>{@code expiresAt} slides on every rotation so a chain in use never reaches it;
 * {@code absoluteExpiresAt} is stamped once at login and copied forward unchanged, and the
 * effective expiry is the earlier of the two — the only thing that ends a stolen token whose thief
 * refreshes ahead of the real client and is never seen.
 *
 * <p>{@code chainStartedAt} is carried forward the same way for a different audience: rotation
 * writes a new row, so {@code issuedAt} on the live row answers "last seen" but not "signed in
 * since", and keeping both lets a person recognise a session in their own list.
 *
 * <p>{@code ipAddress} and {@code userAgent} are stamped on every issue rather than carried
 * forward, so they describe where the session is now; they are labels only, since both are
 * caller-supplied and a session moving between networks is ordinary.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 of the token, hex-encoded — 64 characters, and never the token itself. */
    @jakarta.persistence.Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /* LAZY because only the rotation path needs the user, and it is on the critical path of every
     * request after an access token lapses. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @jakarta.persistence.Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @jakarta.persistence.Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * The chain's hard deadline. Set at first issue, copied forward untouched on every rotation, so
     * it is the same instant for every token descended from one login.
     */
    @jakarta.persistence.Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    /**
     * When the chain this token belongs to began. Set at the login that started it, copied forward
     * untouched on every rotation - so it is the same instant for every token descended from one
     * sign-in, and it is what a device list shows as "signed in".
     */
    @jakarta.persistence.Column(name = "chain_started_at", nullable = false)
    private Instant chainStartedAt;

    /** Where the most recent issue came from. Null for rows written before {@code V9}. */
    @jakarta.persistence.Column(name = "ip_address", length = 45)
    private String ipAddress;

    /** The browser that most recently used the chain, truncated to fit. Null if it sent none. */
    @jakarta.persistence.Column(name = "user_agent", length = 255)
    private String userAgent;

    /** Null while the token is live. Set on rotation, on logout, and on a password change. */
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
