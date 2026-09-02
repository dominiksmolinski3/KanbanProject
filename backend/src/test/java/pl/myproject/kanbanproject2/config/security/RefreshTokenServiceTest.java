package pl.myproject.kanbanproject2.config.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.auth.ActiveDeviceMapper;
import pl.myproject.kanbanproject2.user.auth.DeviceContext;
import pl.myproject.kanbanproject2.user.auth.RefreshToken;
import pl.myproject.kanbanproject2.user.auth.RefreshTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
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
        service = new RefreshTokenService(repository, new ActiveDeviceMapper(),
                TTL.toMillis(), ABSOLUTE_TTL.toMillis(), Clock.fixed(NOW, ZoneOffset.UTC));
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
        return new RefreshToken(digestOf(token), user, issuedAt, expiresAt, absoluteExpiresAt,
                issuedAt, DeviceContext.unknown());
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
            String token = service.issue(user, DeviceContext.unknown()).token();

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
            assertThat(service.issue(user, DeviceContext.unknown()).token()).hasSize(43).doesNotContain("=");
        }

        @Test
        @DisplayName("two issues are never the same token")
        void issuesAreDistinct() {
            assertThat(service.issue(user, DeviceContext.unknown()).token()).isNotEqualTo(service.issue(user, DeviceContext.unknown()).token());
        }

        @Test
        @DisplayName("expiry is the configured lifetime from now, not a hard-coded one")
        void expiryFollowsTheConfiguredLifetime() {
            service.issue(user, DeviceContext.unknown()).token();

            assertThat(lastSaved().getIssuedAt()).isEqualTo(NOW);
            assertThat(lastSaved().getExpiresAt()).isEqualTo(NOW.plus(TTL));
            assertThat(service.getExpirationTime()).isEqualTo(TTL.toMillis());
        }

        @Test
        @DisplayName("a fresh token starts a chain whose ceiling is the absolute lifetime from now")
        void issueStampsTheChainCeiling() {
            service.issue(user, DeviceContext.unknown()).token();

            // The sliding window is the earlier of the two here, so it is what expires_at reads;
            // the ceiling sits behind it, waiting for the window to catch up over repeated rotations.
            assertThat(lastSaved().getExpiresAt()).isEqualTo(NOW.plus(TTL));
            assertThat(lastSaved().getAbsoluteExpiresAt()).isEqualTo(NOW.plus(ABSOLUTE_TTL));
        }

        @Test
        @DisplayName("a non-positive lifetime is refused at construction rather than at midnight")
        void refusesANonPositiveLifetime() {
            assertThatThrownBy(() ->
                    new RefreshTokenService(repository, new ActiveDeviceMapper(), 0, ABSOLUTE_TTL.toMillis(),
                            Clock.systemUTC()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refresh-expiration-time");
        }

        @Test
        @DisplayName("a ceiling below the sliding window is refused - it would make the window dead config")
        void refusesACeilingBelowTheWindow() {
            assertThatThrownBy(() -> new RefreshTokenService(repository, new ActiveDeviceMapper(),
                    TTL.toMillis(), TTL.toMillis() - 1, Clock.systemUTC()))
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

            var rotation = service.rotate("presented", DeviceContext.unknown());

            assertThat(rotation.user()).isSameAs(user);
            assertThat(rotation.refreshToken()).isNotEqualTo("presented");
            assertThat(live.isRevoked()).as("the presented token must not survive its own use").isTrue();
            assertThat(live.getRevokedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("an unknown token is 401 and touches nothing")
        void anUnknownTokenIsRejected() {
            when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rotate("never-issued", DeviceContext.unknown()))
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

            assertThatThrownBy(() -> service.rotate("old", DeviceContext.unknown()))
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

            assertThatThrownBy(() -> service.rotate("edge", DeviceContext.unknown())).isInstanceOf(GlobalException.class);
        }

        @Test
        @DisplayName("replaying a spent token withdraws every session the account has")
        void reusingASpentTokenWithdrawsTheChain() {
            RefreshToken spent = stored("spent", NOW.minusSeconds(60), NOW.plus(TTL));
            spent.revokeAt(NOW.minusSeconds(30));
            when(repository.findByTokenHash(digestOf("spent"))).thenReturn(Optional.of(spent));
            when(repository.revokeAllForUser(user, NOW)).thenReturn(3);

            assertThatThrownBy(() -> service.rotate("spent", DeviceContext.unknown()))
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

            service.rotate("presented", DeviceContext.unknown());

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

            service.rotate("presented", DeviceContext.unknown());

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

            assertThatThrownBy(() -> service.rotate("done", DeviceContext.unknown()))
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

    /**
     * Sessions as something the person holding them can see and end one at a time.
     *
     * <p>V7 made a session a row so the server could end one; what it could not do was aim. The
     * only two revocations were "this one" (logout, which needs the token) and "all of them" (a
     * password change), and the case in between - a laptop left somewhere, and a session you would
     * rather keep - had no answer that did not sign you out everywhere.
     */
    @Nested
    @DisplayName("sessions a person can see")
    class Sessions {

        private RefreshToken saved(long id, Instant issuedAt, Instant expiresAt, DeviceContext device) {
            var row = new RefreshToken(digestOf("t" + id), user, issuedAt, expiresAt,
                    issuedAt.plus(ABSOLUTE_TTL), issuedAt, device);
            setId(row, id);
            return row;
        }

        /** The id is generated by the database; a unit test has to put one there itself. */
        private void setId(RefreshToken row, long id) {
            try {
                var field = RefreshToken.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(row, id);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Test
        @DisplayName("issuing writes down where the session is being used from")
        void issuingRecordsTheDevice() {
            service.issue(user, new DeviceContext("203.0.113.7", "Mozilla/5.0"));

            assertThat(lastSaved().getIpAddress()).isEqualTo("203.0.113.7");
            assertThat(lastSaved().getUserAgent()).isEqualTo("Mozilla/5.0");
            assertThat(lastSaved().getChainStartedAt())
                    .as("a fresh chain starts now, and this is the instant a device list shows")
                    .isEqualTo(NOW);
        }

        @Test
        @DisplayName("issuing hands back the row id, which is how the client recognises itself in the list")
        void issuingReturnsTheRowId() {
            when(repository.save(any(RefreshToken.class))).thenAnswer(call -> {
                RefreshToken row = call.getArgument(0);
                setId(row, 42L);
                return row;
            });

            assertThat(service.issue(user, DeviceContext.unknown()).sessionId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("rotation carries the sign-in forward and takes the current device details")
        void rotationKeepsTheSignInAndUpdatesTheDevice() {
            Instant signedIn = NOW.minus(Duration.ofDays(3));
            var live = new RefreshToken(digestOf("live"), user, signedIn, NOW.plus(TTL),
                    signedIn.plus(ABSOLUTE_TTL), signedIn,
                    new DeviceContext("198.51.100.4", "Firefox/1"));
            when(repository.findByTokenHash(digestOf("live"))).thenReturn(Optional.of(live));

            service.rotate("live", new DeviceContext("203.0.113.9", "Firefox/2"));

            RefreshToken issued = lastSaved();
            assertThat(issued.getChainStartedAt())
                    .as("the session did not start again; it was renewed")
                    .isEqualTo(signedIn);
            assertThat(issued.getIpAddress()).isEqualTo("203.0.113.9");
            assertThat(issued.getUserAgent()).isEqualTo("Firefox/2");
        }

        @Test
        @DisplayName("the listing asks for live rows only, newest renewal first")
        void theListingAsksForLiveRowsOnly() {
            when(repository.findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(user, NOW))
                    .thenReturn(List.of(saved(1L, NOW, NOW.plus(TTL),
                            new DeviceContext("203.0.113.7", "Mozilla/5.0"))));

            var sessions = service.listSessionsFor(user);

            assertThat(sessions).singleElement().satisfies(session -> {
                assertThat(session.id()).isEqualTo(1L);
                assertThat(session.ipAddress()).isEqualTo("203.0.113.7");
                assertThat(session.lastSeenAt()).isEqualTo(NOW);
            });
            verify(repository)
                    .findByUserAndRevokedAtIsNullAndExpiresAtAfterOrderByIssuedAtDesc(user, NOW);
            verifyNoMoreInteractions(repository);
        }

        @Test
        @DisplayName("ending one session withdraws exactly that row")
        void revokingOneSessionWithdrawsThatRow() {
            var live = saved(5L, NOW, NOW.plus(TTL), DeviceContext.unknown());
            when(repository.findById(5L)).thenReturn(Optional.of(live));

            service.revokeSession(user, 5L);

            assertThat(live.getRevokedAt()).isEqualTo(NOW);
            verify(repository).save(live);
        }

        @Test
        @DisplayName("somebody else's session is 404, not 403 - ids here are sequential")
        void anotherAccountsSessionIsNotFound() {
            var other = new User("other", "other@example.test", "hashed");
            other.setId(9);
            var theirs = new RefreshToken(digestOf("theirs"), other, NOW, NOW.plus(TTL),
                    NOW.plus(ABSOLUTE_TTL), NOW, DeviceContext.unknown());
            setId(theirs, 5L);
            when(repository.findById(5L)).thenReturn(Optional.of(theirs));

            assertThatThrownBy(() -> service.revokeSession(user, 5L))
                    .isInstanceOf(GlobalException.class)
                    .extracting("identifier")
                    .isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);

            assertThat(theirs.getRevokedAt()).isNull();
            verify(repository, never()).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("a session that is already over answers the same way, so the list cannot be used to probe")
        void anAlreadyEndedSessionIsNotFound() {
            var spent = saved(6L, NOW.minus(Duration.ofDays(1)), NOW.plus(TTL), DeviceContext.unknown());
            spent.revokeAt(NOW.minus(Duration.ofHours(1)));
            when(repository.findById(6L)).thenReturn(Optional.of(spent));

            assertThatThrownBy(() -> service.revokeSession(user, 6L))
                    .isInstanceOf(GlobalException.class)
                    .extracting("identifier")
                    .isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("an id that names nothing is the same 404 as one that names somebody else's")
        void anUnknownIdIsNotFound() {
            when(repository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.revokeSession(user, 404L))
                    .isInstanceOf(GlobalException.class)
                    .extracting("identifier")
                    .isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("an expired row is not a session anybody is holding, so it cannot be ended either")
        void anExpiredSessionIsNotFound() {
            var lapsed = saved(7L, NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(1)),
                    DeviceContext.unknown());
            when(repository.findById(7L)).thenReturn(Optional.of(lapsed));

            assertThatThrownBy(() -> service.revokeSession(user, 7L))
                    .isInstanceOf(GlobalException.class)
                    .extracting("identifier")
                    .isEqualTo(ExceptionIdentifier.SESSION_NOT_FOUND);
        }
    }
}
