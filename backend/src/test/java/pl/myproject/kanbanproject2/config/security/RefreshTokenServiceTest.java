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

    private RefreshTokenRepository repository;
    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(RefreshTokenRepository.class);
        service = new RefreshTokenService(repository, TTL.toMillis(), Clock.fixed(NOW, ZoneOffset.UTC));
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
        return new RefreshToken(digestOf(token), user, issuedAt, expiresAt);
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
        @DisplayName("a non-positive lifetime is refused at construction rather than at midnight")
        void refusesANonPositiveLifetime() {
            assertThatThrownBy(() -> new RefreshTokenService(repository, 0, Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refresh-expiration-time");
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
