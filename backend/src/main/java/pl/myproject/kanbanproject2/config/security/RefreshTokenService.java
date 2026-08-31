package pl.myproject.kanbanproject2.config.security;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.auth.RefreshToken;
import pl.myproject.kanbanproject2.user.auth.RefreshTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues, rotates and withdraws refresh tokens - the half of the session an access token cannot be.
 *
 * <p>It lives here beside {@link JwtService}, {@link AuthenticationService} and
 * {@link PasswordResetService} because this is where the project keeps the machinery that decides
 * who is signed in; the entity and its repository sit in {@code user.auth} with the rest of the
 * auth model, following the same split those services already use.
 *
 * <p><strong>Rotation, and what happens when a rotated token comes back.</strong> Every successful
 * refresh withdraws the token it was given and issues a new one, so a refresh token is worth
 * exactly one use. That makes replay detectable: a token presented after it has already been
 * rotated means two parties hold the same chain, and the honest reading is that one of them stole
 * it. There is no way to tell which, so the whole chain goes - every live token the account holds
 * is withdrawn and the person signs in again. Anything gentler here would leave the thief in
 * possession of a working session.
 */
@Service
@Slf4j
public class RefreshTokenService {

    /** 256 bits from {@link SecureRandom}. There is nothing to guess and nothing to enumerate. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository refreshTokens;
    private final Duration refreshExpiration;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(
            RefreshTokenRepository refreshTokens,
            @Value("${security.jwt.refresh-expiration-time}") long refreshExpirationMillis
    ) {
        this(refreshTokens, refreshExpirationMillis, Clock.systemUTC());
    }

    RefreshTokenService(RefreshTokenRepository refreshTokens, long refreshExpirationMillis, Clock clock) {
        if (refreshExpirationMillis < 1) {
            throw new IllegalArgumentException("security.jwt.refresh-expiration-time must be positive");
        }
        this.refreshTokens = refreshTokens;
        this.refreshExpiration = Duration.ofMillis(refreshExpirationMillis);
        this.clock = clock;
    }

    public long getExpirationTime() {
        return refreshExpiration.toMillis();
    }

    /**
     * Issues a fresh token for an account that has just proved who it is.
     *
     * <p>Returns the raw token, which is the only moment it exists anywhere outside the caller's
     * hands: what is stored is its digest.
     */
    @Transactional
    public String issue(User user) {
        Instant now = clock.instant();
        String token = ENCODER.encodeToString(randomBytes());
        refreshTokens.save(new RefreshToken(hash(token), user, now, now.plus(refreshExpiration)));
        return token;
    }

    /**
     * Exchanges a live token for a new one, and answers with the account it belongs to.
     *
     * <p>Every failure is one status - {@code 401 INVALID_CREDENTIALS} - for the reason the rest of
     * this package already follows: an unknown token, an expired one and a withdrawn one are three
     * facts about the session and one answer to the caller, and separating them would say which
     * tokens have ever existed.
     */
    @Transactional
    public Rotation rotate(String presented) {
        Instant now = clock.instant();
        RefreshToken stored = refreshTokens.findByTokenHash(hash(presented))
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS));

        if (stored.isRevoked()) {
            int withdrawn = refreshTokens.revokeAllForUser(stored.getUser(), now);
            log.warn("Refresh token reuse detected for user {}; withdrew {} live session(s)",
                    stored.getUser().getId(), withdrawn);
            throw new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS);
        }
        if (stored.isExpiredAt(now)) {
            throw new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS);
        }

        stored.revokeAt(now);
        refreshTokens.save(stored);

        User user = stored.getUser();
        return new Rotation(user, issue(user));
    }

    /**
     * Withdraws one token, and says nothing about whether it existed.
     *
     * <p>Logging out is not a place to fail. A token that is unknown, already spent or expired
     * leaves the caller in exactly the state they asked for, so every case answers the same
     * {@code 204} - and answering differently would turn logout into a way to test whether a token
     * is live.
     */
    @Transactional
    public void revoke(String presented) {
        refreshTokens.findByTokenHash(hash(presented))
                .filter(token -> !token.isRevoked())
                .ifPresent(token -> {
                    token.revokeAt(clock.instant());
                    refreshTokens.save(token);
                });
    }

    /**
     * Withdraws every live session an account holds.
     *
     * <p>Called when the password changes, by either route. A password change that left older
     * sessions running would mean the person who changed it because somebody else had their
     * password had not actually locked them out - which is the whole reason they changed it.
     */
    @Transactional
    public int revokeAllFor(User user) {
        int withdrawn = refreshTokens.revokeAllForUser(user, clock.instant());
        if (withdrawn > 0) {
            log.info("Withdrew {} refresh token(s) for user {}", withdrawn, user.getId());
        }
        return withdrawn;
    }

    /**
     * Drops rows past their own expiry, daily.
     *
     * <p>The same shape as the deadline sweep in {@code TaskService}: this table only ever grows,
     * and a row whose token has expired is rejected by the expiry check whether it is there or not.
     */
    @Scheduled(fixedRateString = "${security.jwt.refresh-cleanup-rate:86400000}")
    @Transactional
    public void deleteExpiredTokens() {
        int deleted = refreshTokens.deleteExpiredBefore(clock.instant());
        if (deleted > 0) {
            log.info("Deleted {} expired refresh token(s)", deleted);
        }
    }

    public record Rotation(User user, String refreshToken) {
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * SHA-256, hex-encoded. Not a password hash and deliberately not a slow one: the input is 256
     * random bits, so there is no dictionary to run and a work factor would buy nothing but latency
     * on the path every client takes when its access token lapses.
     */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JRE; if it is missing, the platform is not one.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
