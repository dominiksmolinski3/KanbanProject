package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.auth.RefreshToken;
import pl.myproject.kanbanproject2.user.auth.RefreshTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * What a refresh token has to be for it to be worth having at all.
 *
 * <p>Four properties, and each of them is the reason one of the others is safe. The stored value is
 * a digest, so the table is not a set of live credentials. A token is single-use, so a stolen one
 * is only good until the real client next refreshes. A token presented twice withdraws the whole
 * chain, so that theft is loud rather than silent. And every failure answers the same status, so
 * none of the above can be probed from outside.
 *
 * <p>The clock is injected. Expiry is the one rule that cannot be asserted honestly by a test that
 * waits, and {@code AuthRateLimiter} already established the pattern here.
 */
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private static final Duration TTL = Duration.ofDays(30);
    private static final Duration ABSOLUTE_TTL = Duration.ofDays(90);

    private RefreshTokenRepository repository;
    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        service = new RefreshTokenService(
                repository, TTL.toMillis(), ABSOLUTE_TTL.toMillis(), Clock.fixed(NOW, ZoneOffset.UTC));
        user = new User("someone", "someone@example.test", "hashed");
        user.setId(7);

        when(repository.save(any(RefreshToken.class))).thenAnswer(call -> call.getArgument(0));
    }

    /** The digest the service stores, computed independently so the test does not trust it. */
    private static String digestOf(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RefreshToken stored(String token, Instant issuedAt, Instant expiresAt) {
        return stored(token, issuedAt, expiresAt, issuedAt.plus(ABSOLUTE_TTL));
    }

    private RefreshToken stored(String token, Instant issuedAt, Instant expiresAt, Instant absoluteExpiresAt) {
        return new RefreshToken(digestOf(token), user, issuedAt, expiresAt, absoluteExpiresAt);
    }

    private RefreshToken lastSaved() {
        var saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getValue();
    }

    @Nested
    @DisplayName("issuing")
    class Issuing {

        @Test
        @DisplayName("stores a digest and never the token itself")
        void storesOnlyTheDigest() {
            String token = service.issue(user);

            RefreshToken row = lastSaved();
            assertThat(row.getTokenHash())
                    .as("the stored value must not be the credential")
                    .isNotEqualTo(token)
                    .isEqualTo(digestOf(token))
                    .hasSize(64);
        }

        @Test
        @DisplayName("the token is long enough that guessing is not a threat model")
        void theTokenIsLong() {
            // 32 random bytes, base64url without padding: 43 characters, 256 bits of entropy.
            assertThat(service.issue(user)).hasSize(43).doesNotContain("=");
        }

        @Test
        @DisplayName("two issues are never the same token")
        void issuesAreDistinct() {
            assertThat(service.issue(user)).isNotEqualTo(service.issue(user));
        }

        @Test
        @DisplayName("expiry is the configured lifetime from now, not a hard-coded one")
        void expiryFollowsTheConfiguredLifetime() {
            service.issue(user);

            assertThat(lastSaved().getIssuedAt()).isEqualTo(NOW);
            assertThat(lastSaved().getExpiresAt()).isEqualTo(NOW.plus(TTL));
            assertThat(service.getExpirationTime()).isEqualTo(TTL.toMillis());
        }

        @Test
        @DisplayName("a fresh token starts a chain whose ceiling is the absolute lifetime from now")
        void issueStampsTheChainCeiling() {
            service.issue(user);

            // The sliding window is the earlier of the two here, so it is what expires_at reads;
            // the ceiling sits behind it, waiting for the window to catch up over repeated rotations.
            assertThat(lastSaved().getExpiresAt()).isEqualTo(NOW.plus(TTL));
            assertThat(lastSaved().getAbsoluteExpiresAt()).isEqualTo(NOW.plus(ABSOLUTE_TTL));
        }

        @Test
        @DisplayName("a non-positive lifetime is refused at construction rather than at midnight")
        void refusesANonPositiveLifetime() {
            assertThatThrownBy(() ->
                    new RefreshTokenService(repository, 0, ABSOLUTE_TTL.toMillis(), Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refresh-expiration-time");
        }

        @Test
        @DisplayName("a ceiling below the sliding window is refused - it would make the window dead config")
        void refusesACeilingBelowTheWindow() {
            assertThatThrownBy(() -> new RefreshTokenService(
                    repository, TTL.toMillis(), TTL.toMillis() - 1, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refresh-absolute-expiration-time");
        }
    }

    @Nested
    @DisplayName("rotating")
    class Rotating {

        @Test
        @DisplayName("a live token is exchanged for a different one, and the old one is spent")
        void rotationSpendsThePresentedToken() {
            RefreshToken live = stored("presented", NOW.minus(Duration.ofDays(1)), NOW.plus(TTL));
            when(repository.findByTokenHash(digestOf("presented"))).thenReturn(Optional.of(live));

            var rotation = service.rotate("presented");

            assertThat(rotation.user()).isSameAs(user);
            assertThat(rotation.refreshToken()).isNotEqualTo("presented");
            assertThat(live.isRevoked()).as("the presented token must not survive its own use").isTrue();
            assertThat(live.getRevokedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("an unknown token is 401 and touches nothing")
        void anUnknownTokenIsRejected() {
            when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rotate("never-issued"))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_CREDENTIALS);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("an expired token is 401 - the same answer an unknown one gets")
        void anExpiredTokenIsRejected() {
            RefreshToken lapsed = stored("old", NOW.minus(TTL.plusDays(1)), NOW.minus(Duration.ofSeconds(1)));
            when(repository.findByTokenHash(digestOf("old"))).thenReturn(Optional.of(lapsed));

            assertThatThrownBy(() -> service.rotate("old"))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_CREDENTIALS);

            verify(repository, never()).save(any());
            verify(repository, never()).revokeAllForUser(any(), any());
        }

        @Test
        @DisplayName("a token expiring exactly now is already gone, not still good")
        void expiryIsInclusive() {
            RefreshToken onTheBoundary = stored("edge", NOW.minus(TTL), NOW);
            when(repository.findByTokenHash(digestOf("edge"))).thenReturn(Optional.of(onTheBoundary));

            assertThatThrownBy(() -> service.rotate("edge")).isInstanceOf(GlobalException.class);
        }

        @Test
        @DisplayName("replaying a spent token withdraws every session the account has")
        void reusingASpentTokenWithdrawsTheChain() {
            RefreshToken spent = stored("spent", NOW.minusSeconds(60), NOW.plus(TTL));
            spent.revokeAt(NOW.minusSeconds(30));
            when(repository.findByTokenHash(digestOf("spent"))).thenReturn(Optional.of(spent));
            when(repository.revokeAllForUser(user, NOW)).thenReturn(3);

            assertThatThrownBy(() -> service.rotate("spent"))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_CREDENTIALS);

            // The point of the whole design: a second holder of the chain ends both sessions, not
            // just its own, because there is no way to tell the thief from the owner.
            verify(repository).revokeAllForUser(user, NOW);
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("the absolute ceiling")
    class AbsoluteCeiling {

        @Test
        @DisplayName("rotation carries the chain ceiling forward without pushing it out")
        void rotationDoesNotExtendTheCeiling() {
            // Chain started forty days ago; its ceiling is fifty days from now (ninety from the start).
            Instant ceiling = NOW.plus(Duration.ofDays(50));
            RefreshToken live = stored("presented", NOW.minus(Duration.ofDays(40)), NOW.plus(TTL), ceiling);
            when(repository.findByTokenHash(digestOf("presented"))).thenReturn(Optional.of(live));

            service.rotate("presented");

            // Not NOW.plus(ABSOLUTE_TTL): a rotation is not a new login and does not reset the ceiling.
            assertThat(lastSaved().getAbsoluteExpiresAt()).isEqualTo(ceiling);
            assertThat(lastSaved().getExpiresAt())
                    .as("the sliding window is still the earlier deadline this far from the ceiling")
                    .isEqualTo(NOW.plus(TTL));
        }

        @Test
        @DisplayName("near the ceiling the replacement is capped at it, not given a full window")
        void theWindowCannotSlidePastTheCeiling() {
            Instant ceiling = NOW.plus(Duration.ofDays(10));
            RefreshToken live = stored("presented", NOW.minus(Duration.ofDays(80)), NOW.plus(TTL), ceiling);
            when(repository.findByTokenHash(digestOf("presented"))).thenReturn(Optional.of(live));

            service.rotate("presented");

            assertThat(lastSaved().getExpiresAt())
                    .as("ten days left on the chain, not thirty")
                    .isEqualTo(ceiling);
            assertThat(lastSaved().getAbsoluteExpiresAt()).isEqualTo(ceiling);
        }

        @Test
        @DisplayName("once the ceiling has passed the chain is finished, however often it rotated")
        void aChainPastItsCeilingCannotRotate() {
            // A token issued right at the ceiling: capped, so expires_at and the ceiling coincide,
            // and both are now in the past.
            Instant ceiling = NOW.minus(Duration.ofSeconds(1));
            RefreshToken done = stored("done", NOW.minus(ABSOLUTE_TTL), ceiling, ceiling);
            when(repository.findByTokenHash(digestOf("done"))).thenReturn(Optional.of(done));

            assertThatThrownBy(() -> service.rotate("done"))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_CREDENTIALS);

            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("withdrawing")
    class Withdrawing {

        @Test
        @DisplayName("logout marks the token revoked")
        void logoutRevokes() {
            RefreshToken live = stored("live", NOW.minusSeconds(60), NOW.plus(TTL));
            when(repository.findByTokenHash(digestOf("live"))).thenReturn(Optional.of(live));

            service.revoke("live");

            assertThat(live.isRevoked()).isTrue();
            assertThat(live.getRevokedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("logging out with an unknown token is not an error and writes nothing")
        void logoutOfAnUnknownTokenIsSilent() {
            when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

            service.revoke("never-issued");

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("logging out twice does not re-stamp the revocation time")
        void logoutIsIdempotent() {
            RefreshToken alreadyGone = stored("gone", NOW.minusSeconds(120), NOW.plus(TTL));
            alreadyGone.revokeAt(NOW.minusSeconds(60));
            when(repository.findByTokenHash(digestOf("gone"))).thenReturn(Optional.of(alreadyGone));

            service.revoke("gone");

            assertThat(alreadyGone.getRevokedAt()).isEqualTo(NOW.minusSeconds(60));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a password change withdraws every live session in one statement")
        void revokeAllForUserIsOneStatement() {
            when(repository.revokeAllForUser(user, NOW)).thenReturn(2);

            assertThat(service.revokeAllFor(user)).isEqualTo(2);

            verify(repository).revokeAllForUser(user, NOW);
            verifyNoMoreInteractions(repository);
        }
    }

    @Nested
    @DisplayName("housekeeping")
    class Housekeeping {

        @Test
        @DisplayName("the sweep drops rows past their own expiry and nothing else")
        void theSweepDeletesOnlyExpiredRows() {
            when(repository.deleteExpiredBefore(NOW)).thenReturn(5);

            service.deleteExpiredTokens();

            verify(repository).deleteExpiredBefore(eq(NOW));
            verifyNoMoreInteractions(repository);
        }
    }
}
