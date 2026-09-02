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
     * Issues a fresh token for an account that has just proved who it is.
     *
     * <p>This starts a new chain, so both of the instants a chain carries are set here: the
     * absolute deadline at {@code now} plus the configured ceiling, and the sign-in at {@code now}.
     * Every rotation after this carries the same two forward.
     *
     * <p>Returns the raw token, which is the only moment it exists anywhere outside the caller's
     * hands - what is stored is its digest - alongside the id of the row it was written to, which
     * is the client's answer to which of these sessions is mine. The id names a row, not a
     * credential: it is worth nothing to anyone not already signed in as this account.
     */
    @Transactional
    public Issued issue(User user, DeviceContext device) {
        Instant now = clock.instant();
        return issueWithin(user, now.plus(absoluteExpiration), now, now, device);
    }

    /**
     * Every session the account can still use, as something a person can read.
     *
     * <p>Lives here rather than in a service of its own because this class is the only thing in the
     * application that reads {@code refresh_tokens}, and that is worth keeping true: a second
     * reader is a second place where "live" could come to mean something slightly different.
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
     * Ends one named session, and refuses to say anything about a row that is not the caller's.
     *
     * <p>{@code SESSION_NOT_FOUND} covers three cases on purpose: no such row, somebody else's row,
     * and a row already withdrawn or expired. Separating them would turn sequential ids into a way
     * to count other people's sessions - the reasoning the board routes already follow. A caller
     * can only act on what the list handed them.
     *
     * <p>Unlike {@code /auth/logout}, this one does report failure. Logging out asks to end the
     * session you are holding, and a caller who is already signed out has what they asked for;
     * pressing "sign out" beside a device in a list asks about one specific session, and answering
     * 204 for a row nothing touched would tell somebody their lost phone had been signed out when
     * it had not.
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
     * Writes one token into an existing (or new) chain.
     *
     * <p>{@code expiresAt} is the earlier of the sliding window and {@code chainDeadline}: while the
     * window is the earlier one the token renews normally, and once the chain has run long enough
     * that the window would reach past its ceiling the token is capped at the ceiling instead. A
     * token issued at that point expires when the chain does, and the rotation after it is refused
     * by the same expiry check that rejects any lapsed token.
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
     * Exchanges a live token for a new one, and answers with the account it belongs to.
     *
     * <p>Every failure is one status - {@code 401 INVALID_CREDENTIALS} - for the reason the rest of
     * this package already follows: an unknown token, an expired one and a withdrawn one are three
     * facts about the session and one answer to the caller, and separating them would say which
     * tokens have ever existed.
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
