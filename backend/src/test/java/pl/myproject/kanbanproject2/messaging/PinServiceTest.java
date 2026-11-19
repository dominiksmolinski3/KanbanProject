package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.user.User;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PinServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final byte[] POINT = HexFormat.of().parseHex(
            "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2"
                    + "964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");

    private IdentityKeyRepository identities;
    private IdentityPinRepository pins;
    private PinService service;
    private User caller;
    private Ed25519TestKeys identityKeys;

    private PinService serviceWith(boolean enabled, String secret) {
        var properties = new MessagingProperties(enabled, secret);
        return new PinService(properties, new PinEvaluator(properties), identities, pins,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        identities = mock(IdentityKeyRepository.class);
        pins = mock(IdentityPinRepository.class);
        service = serviceWith(true, "test-pin-secret");
        caller = new User("someone", "someone@example.test", "hashed");
        caller.setId(7);
        identityKeys = Ed25519TestKeys.generate();
        when(identities.findById(7)).thenReturn(Optional.of(
                new IdentityKey(caller, identityKeys.publicKey(), null, NOW)));
    }

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private IdentityPin storedPin(byte[] proof, int failures) throws Exception {
        var pin = new IdentityPin(caller, new byte[]{5, 5}, sha256(proof), NOW);
        ReflectionTestUtils.setField(pin, "failedAttempts", failures);
        return pin;
    }

    @Test
    @DisplayName("without the feature or a configured secret every PIN route answers as if it did not exist")
    void unavailable() {
        for (PinService off : List.of(serviceWith(false, "x"), serviceWith(true, ""))) {
            assertThatThrownBy(() -> off.evaluate(caller, new PinEvaluationRequest(POINT)))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.MESSAGING_UNAVAILABLE);
            assertThatThrownBy(() -> off.unlock(caller, new PinUnlockRequest(new byte[32])))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.MESSAGING_UNAVAILABLE);
            assertThatThrownBy(() -> off.setPin(caller, new PinRequest(new byte[1], new byte[32], new byte[64])))
                    .extracting("identifier").isEqualTo(ExceptionIdentifier.MESSAGING_UNAVAILABLE);
        }
        assertThat(serviceWith(true, "").status(caller).available()).isFalse();
    }

    @Test
    @DisplayName("evaluating while no PIN is set costs nothing")
    void evaluatingBeforeAPinIsFree() {
        when(pins.existsById(7)).thenReturn(false);

        assertThat(service.evaluate(caller, new PinEvaluationRequest(POINT)).evaluated()).hasSize(32);
        verify(pins, never()).spendAttempt(any(), any(Integer.class));
    }

    @Test
    @DisplayName("each evaluation of a set PIN is charged, and once ten are spent the PIN is locked")
    void evaluationsAreCharged() {
        when(pins.existsById(7)).thenReturn(true);
        when(pins.spendAttempt(7, 10)).thenReturn(1, 0);

        assertThat(service.evaluate(caller, new PinEvaluationRequest(POINT)).evaluated()).hasSize(32);
        assertThatThrownBy(() -> service.evaluate(caller, new PinEvaluationRequest(POINT)))
                .extracting("identifier").isEqualTo(ExceptionIdentifier.PIN_LOCKED);
    }

    @Test
    @DisplayName("a blinded value that is not a point is refused after the attempt is charged")
    void notAPoint() {
        when(pins.existsById(7)).thenReturn(false);
        byte[] offCurve = POINT.clone();
        offCurve[64] ^= 1;

        assertThatThrownBy(() -> service.evaluate(caller, new PinEvaluationRequest(offCurve)))
                .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_CRYPTO_KEY);
    }

    @Test
    @DisplayName("a PIN is stored only when the identity key signed it, and setting one refunds every attempt")
    void settingAPin() throws Exception {
        byte[] wrapped = {1, 2, 3};
        byte[] verifier = sha256(new byte[]{9});
        IdentityPin existing = storedPin(new byte[]{8}, 6);
        when(pins.findById(7)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.setPin(caller,
                new PinRequest(wrapped, verifier, Ed25519TestKeys.generate().signPin(wrapped, verifier))))
                .extracting("identifier").isEqualTo(ExceptionIdentifier.INVALID_CRYPTO_KEY);
        verify(pins, never()).save(any());

        PinStatusDto status = service.setPin(caller, new PinRequest(wrapped, verifier,
                identityKeys.signPin(wrapped, verifier)));

        assertThat(status).isEqualTo(new PinStatusDto(true, true, 10));
        assertThat(existing.getFailedAttempts()).isZero();
        assertThat(existing.getWrappedPrivate()).containsExactly(1, 2, 3);
        verify(pins).save(existing);
    }

    @Test
    @DisplayName("a first PIN creates its row; there is none to set before an identity exists")
    void firstPin() throws Exception {
        byte[] wrapped = {1};
        byte[] verifier = sha256(new byte[]{9});
        when(pins.findById(7)).thenReturn(Optional.empty());

        service.setPin(caller, new PinRequest(wrapped, verifier, identityKeys.signPin(wrapped, verifier)));
        verify(pins).save(any(IdentityPin.class));

        when(identities.findById(7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setPin(caller, new PinRequest(wrapped, verifier, new byte[64])))
                .extracting("identifier").isEqualTo(ExceptionIdentifier.IDENTITY_KEY_NOT_FOUND);
    }

    @Test
    @DisplayName("the right proof hands back the PIN-wrapped key and refunds the attempts spent")
    void unlocking() throws Exception {
        byte[] proof = new byte[32];
        proof[0] = 4;
        IdentityPin pin = storedPin(proof, 3);
        when(pins.findById(7)).thenReturn(Optional.of(pin));

        IdentityBackupDto unlocked = service.unlock(caller, new PinUnlockRequest(proof));

        assertThat(unlocked.wrappedPrivate()).containsExactly(5, 5);
        assertThat(unlocked.publicKey()).isEqualTo(identityKeys.publicKey());
        assertThat(pin.getFailedAttempts()).isZero();
        assertThat(service.status(caller)).isEqualTo(new PinStatusDto(true, true, 10));
    }

    @Test
    @DisplayName("the tenth guess can still be right")
    void theLastGuessCounts() throws Exception {
        byte[] proof = new byte[32];
        when(pins.findById(7)).thenReturn(Optional.of(storedPin(proof, 10)));

        assertThat(service.unlock(caller, new PinUnlockRequest(proof)).wrappedPrivate()).containsExactly(5, 5);
    }

    @Test
    @DisplayName("a wrong proof keeps the attempt spent, and the last wrong one says the PIN is locked")
    void wrongProof() throws Exception {
        byte[] proof = new byte[32];
        byte[] wrong = new byte[32];
        wrong[31] = 1;
        IdentityPin pin = storedPin(proof, 4);
        when(pins.findById(7)).thenReturn(Optional.of(pin));

        assertThatThrownBy(() -> service.unlock(caller, new PinUnlockRequest(wrong)))
                .extracting("identifier").isEqualTo(ExceptionIdentifier.PIN_INCORRECT);
        assertThat(service.status(caller).attemptsLeft()).isEqualTo(6);

        when(pins.findById(7)).thenReturn(Optional.of(storedPin(proof, 10)));
        assertThatThrownBy(() -> service.unlock(caller, new PinUnlockRequest(wrong)))
                .extracting("identifier").isEqualTo(ExceptionIdentifier.PIN_LOCKED);
        verify(pins, never()).save(any());
    }

    @Test
    @DisplayName("there is nothing to unlock on an account with no PIN")
    void noPin() {
        when(pins.findById(7)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unlock(caller, new PinUnlockRequest(new byte[32])))
                .extracting("identifier").isEqualTo(ExceptionIdentifier.PIN_NOT_SET);
        assertThat(service.status(caller)).isEqualTo(new PinStatusDto(true, false, 10));
    }
}
