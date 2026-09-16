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
import pl.myproject.kanbanproject2.user.auth.ActiveDeviceDto;
import pl.myproject.kanbanproject2.user.auth.ActiveDeviceMapper;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
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
import java.util.List;

/**
 * Issues, rotates and withdraws refresh tokens - the half of the session an access token cannot be.
 *
 * <p><strong>Rotation, and what happens when a rotated token comes back.</strong> Every successful
 * refresh withdraws the token it was given and issues a new one, so a refresh token is worth
 * exactly one use; a token presented after it was already rotated means two parties hold the same
 * chain, read as theft. There is no way to tell which party is the thief, so the whole chain is
 * withdrawn and the person signs in again.
 */
@Service
@Slf4j
public class RefreshTokenService {

    /** 256 bits from {@link SecureRandom}. There is nothing to guess and nothing to enumerate. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RefreshTokenRepository refreshTokens;
    private final ActiveDeviceMapper deviceMapper;
    private final Duration refreshExpiration;
    private final Duration absoluteExpiration;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(
            RefreshTokenRepository refreshTokens,
            ActiveDeviceMapper deviceMapper,
            @Value("${security.jwt.refresh-expiration-time}") long refreshExpirationMillis,
            @Value("${security.jwt.refresh-absolute-expiration-time}") long absoluteExpirationMillis
    ) {
        this(refreshTokens, deviceMapper, refreshExpirationMillis, absoluteExpirationMillis,
                Clock.systemUTC());
    }

    RefreshTokenService(RefreshTokenRepository refreshTokens, ActiveDeviceMapper deviceMapper,
                        long refreshExpirationMillis, long absoluteExpirationMillis, Clock clock) {
        if (refreshExpirationMillis < 1) {
            throw new IllegalArgumentException("security.jwt.refresh-expiration-time must be positive");
        }
        if (absoluteExpirationMillis < refreshExpirationMillis) {
            // A ceiling below the sliding window would mean the window never applied - every token
            // would be capped at the ceiling from the first issue. Almost certainly a units mistake.
            throw new IllegalArgumentException(
                    "security.jwt.refresh-absolute-expiration-time must be at least "
                            + "security.jwt.refresh-expiration-time");
        }
        this.refreshTokens = refreshTokens;
        this.deviceMapper = deviceMapper;
        this.refreshExpiration = Duration.ofMillis(refreshExpirationMillis);
        this.absoluteExpiration = Duration.ofMillis(absoluteExpirationMillis);
        this.clock = clock;
    }

    public long getExpirationTime() {
        return refreshExpiration.toMillis();
    }

    /**
     * Issues a fresh token for an account that has just proved who it is, starting a new chain: the
     * absolute deadline and the sign-in instant are both set here and carried forward by every
     * rotation. Returns the raw token - the only moment it exists outside the caller's hands, since
     * what is stored is its digest - alongside the row id the client uses to recognise its own
     * session.
     */
    @Transactional
    public Issued issue(User user, DeviceContext device) {
        Instant now = clock.instant();
        return issueWithin(user, now.plus(absoluteExpiration), now, now, device);
    }

    /**
     * Every session the account can still use, as something a person can read. Lives here rather
     * than in a service of its own because this class is the only thing that reads
     * {@code refresh_tokens} - a second reader would be a second place "live" could drift.
     */
    @Transactional
    public List<ActiveDeviceDto> listSessionsFor(User user) {
        return refreshTokens
                .findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(user, clock.instant())
                .stream()
                .map(deviceMapper::apply)
                .toList();
    }

    /**
     * Ends one named session. {@code SESSION_NOT_FOUND} covers no-such-row, somebody-else's-row and
     * already-withdrawn on purpose - separating them would turn sequential ids into a way to count
     * other people's sessions. Unlike {@code /auth/logout}, this one does report failure: pressing
     * "sign out" beside a device asks about one specific session, and a silent 204 for a row nothing
     * touched would misreport a lost phone as signed out.
     */
    @Transactional
    public void revokeSession(User user, Long sessionId) {
        Instant now = clock.instant();
        RefreshToken session = refreshTokens.findById(sessionId)
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .filter(token -> !token.isRevoked())
                .filter(token -> !token.isExpiredAt(now))
                .orElseThrow(() -> new GlobalException(ExceptionIdentifier.SESSION_NOT_FOUND));

        session.revokeAt(now);
        refreshTokens.save(session);
        log.info("Withdrew session {} for user {} on request", sessionId, user.getId());
    }

    /**
     * Writes one token into an existing (or new) chain. {@code expiresAt} is the earlier of the
     * sliding window and {@code chainDeadline}, so once the chain has run long enough that the
     * window would reach past its ceiling, the token is capped at the ceiling instead and the next
     * rotation is refused by the same expiry check that rejects any lapsed token.
     */
    private Issued issueWithin(User user, Instant chainDeadline, Instant chainStartedAt, Instant now,
                               DeviceContext device) {
        String token = ENCODER.encodeToString(randomBytes());
        Instant slidingDeadline = now.plus(refreshExpiration);
        Instant expiresAt = slidingDeadline.isBefore(chainDeadline) ? slidingDeadline : chainDeadline;
        RefreshToken saved = refreshTokens.save(new RefreshToken(
                hash(token), user, now, expiresAt, chainDeadline, chainStartedAt, device));
        return new Issued(token, saved.getId());
    }

    /**
     * Exchanges a live token for a new one, and answers with the account it belongs to. Every
     * failure is one status, {@code 401 INVALID_CREDENTIALS} - separating unknown, expired and
     * withdrawn would say which tokens have ever existed.
     */
    @Transactional
    public Rotation rotate(String presented, DeviceContext device) {
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
            // Covers the chain ceiling too: once the sliding window reaches it, expiresAt is the
            // ceiling, so a chain past its absolute deadline lands here like any other lapsed token.
            throw new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS);
        }

        stored.revokeAt(now);
        refreshTokens.save(stored);

        User user = stored.getUser();
        // The chain's two fixed points - its ceiling and its sign-in - travel with it untouched;
        // the device details do not, because they describe where the session is being used now.
        Issued issued = issueWithin(
                user, stored.getAbsoluteExpiresAt(), stored.getChainStartedAt(), now, device);
        return new Rotation(user, issued.token(), issued.sessionId());
    }

    /**
     * Withdraws one token, and says nothing about whether it existed. An unknown, already-spent or
     * expired token leaves the caller in exactly the state they asked for, so every case answers
     * the same {@code 204} - answering differently would turn logout into a way to test token
     * liveness.
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
     * Withdraws every live session an account holds, called on a password change by either route -
     * leaving older sessions running would defeat the reason somebody changes a password they
     * suspect is compromised.
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

    public record Rotation(User user, String refreshToken, Long sessionId) {
    }

    /** A raw token and the id of the row it was written to. The token exists only here. */
    public record Issued(String token, Long sessionId) {
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
