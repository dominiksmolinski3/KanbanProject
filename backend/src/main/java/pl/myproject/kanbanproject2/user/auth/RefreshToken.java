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

    /** Null while the token is live. Set on rotation, on logout, and on a password change. */
    @jakarta.persistence.Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
    }

    public RefreshToken(String tokenHash, User user, Instant issuedAt, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.user = user;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
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
