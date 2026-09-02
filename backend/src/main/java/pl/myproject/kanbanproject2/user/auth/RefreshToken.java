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
 * One issued refresh token, as a row that can be withdrawn.
 *
 * <p>This is the whole point of the type. An access token is a signed claim: once it is out, the
 * only thing that ends it is its own expiry, which is why the hour on the JWT was simultaneously a
 * hard session cap and the only bound on a stolen one. A refresh token is a row, and a row can be
 * deleted, so a session becomes something the server can end.
 *
 * <p>Only a SHA-256 digest of the token is stored, never the token. The reasoning is the one
 * {@code password_reset_code} already follows: this value is a credential, and the table is exactly
 * what an attacker with read access has. A digest is enough to recognise a token presented back and
 * useless to anyone who only has the table. It is not password hashing and does not want a slow
 * KDF — the input is 256 bits from {@link java.security.SecureRandom}, so there is no dictionary to
 * run and nothing for a work factor to buy.
 *
 * <p>Rows are kept rather than deleted on revocation. {@code revokedAt} being set is what makes
 * replay of an already-rotated token detectable, and detecting it is what turns a stolen token into
 * a signal instead of a silent second session.
 *
 * <p>{@code expiresAt} slides: every rotation issues a replacement dated the sliding window from
 * now, so a chain in use never reaches it. {@code absoluteExpiresAt} does not - it is stamped once,
 * at the login that starts the chain, and copied forward unchanged on each rotation. The effective
 * expiry a check reads is the earlier of the two, so once the sliding window catches up to the
 * absolute deadline the chain is finished no matter how often it rotates. That ceiling is the only
 * thing that ends a stolen token whose thief refreshes ahead of the real client and is never seen.
 *
 * <p>{@code chainStartedAt} is carried forward the same way and for a different audience. Rotation
 * writes a new row, so {@code issuedAt} on the live row is the last renewal - which is the right
 * answer to "last seen" and the wrong one to "signed in since". Keeping both is what lets a
 * person look at a list of their own sessions and recognise one.
 *
 * <p>{@code ipAddress} and {@code userAgent} are stamped on every issue rather than carried
 * forward, so they describe where the session is now. They are labels: nothing checks them, because
 * both are supplied by the caller and a session that moves between networks is ordinary.
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

    /*
     * LAZY because the only caller that needs the user is the rotation path, and it is on the
     * critical path of every request the client makes after an access token lapses.
     */
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
