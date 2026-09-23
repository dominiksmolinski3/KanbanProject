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

@Service
@Slf4j
public class RefreshTokenService {

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

    @Transactional
    public Issued issue(User user, DeviceContext device) {
        Instant now = clock.instant();
        return issueWithin(user, now.plus(absoluteExpiration), now, now, device);
    }

    @Transactional
    public List<ActiveDeviceDto> listSessionsFor(User user) {
        return refreshTokens
                .findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(user, clock.instant())
                .stream()
                .map(deviceMapper::apply)
                .toList();
    }

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

    private Issued issueWithin(User user, Instant chainDeadline, Instant chainStartedAt, Instant now,
                               DeviceContext device) {
        String token = ENCODER.encodeToString(randomBytes());
        Instant slidingDeadline = now.plus(refreshExpiration);
        Instant expiresAt = slidingDeadline.isBefore(chainDeadline) ? slidingDeadline : chainDeadline;
        RefreshToken saved = refreshTokens.save(new RefreshToken(
                hash(token), user, now, expiresAt, chainDeadline, chainStartedAt, device));
        return new Issued(token, saved.getId());
    }

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
            throw new GlobalException(ExceptionIdentifier.INVALID_CREDENTIALS);
        }

        stored.revokeAt(now);
        refreshTokens.save(stored);

        User user = stored.getUser();
        Issued issued = issueWithin(
                user, stored.getAbsoluteExpiresAt(), stored.getChainStartedAt(), now, device);
        return new Rotation(user, issued.token(), issued.sessionId());
    }

    @Transactional
    public void revoke(String presented) {
        refreshTokens.findByTokenHash(hash(presented))
                .filter(token -> !token.isRevoked())
                .ifPresent(token -> {
                    token.revokeAt(clock.instant());
                    refreshTokens.save(token);
                });
    }

    @Transactional
    public int revokeAllFor(User user) {
        int withdrawn = refreshTokens.revokeAllForUser(user, clock.instant());
        if (withdrawn > 0) {
            log.info("Withdrew {} refresh token(s) for user {}", withdrawn, user.getId());
        }
        return withdrawn;
    }

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

    public record Issued(String token, Long sessionId) {
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
