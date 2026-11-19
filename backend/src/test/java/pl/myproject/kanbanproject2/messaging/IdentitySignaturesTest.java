package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IdentitySignaturesTest {

    private final Ed25519TestKeys identity = Ed25519TestKeys.generate();
    private final byte[] deviceKey = Ed25519TestKeys.generate().publicKey();
    private final byte[] credential = Ed25519TestKeys.credential(7);

    @Test
    @DisplayName("accepts the identity's signature over the label and the parts, in order")
    void acceptsTheIdentitySignature() {
        byte[] signature = identity.signDevice(deviceKey, credential);

        assertThat(IdentitySignatures.verify(identity.publicKey(), signature, IdentitySignatures.DEVICE,
                deviceKey, credential)).isTrue();
        assertThat(IdentitySignatures.verify(identity.publicKey(), signature, IdentitySignatures.DEVICE,
                credential, deviceKey)).isFalse();
    }

    @Test
    @DisplayName("a signature made for one purpose is refused for another")
    void labelsSeparatePurposes() {
        byte[] wrapped = {1, 2, 3};
        byte[] recovery = identity.signRecovery(wrapped);
        byte[] pin = identity.signPin(wrapped, new byte[32]);

        assertThat(IdentitySignatures.verify(identity.publicKey(), recovery, IdentitySignatures.RECOVERY, wrapped))
                .isTrue();
        assertThat(IdentitySignatures.verify(identity.publicKey(), recovery, IdentitySignatures.PIN, wrapped,
                new byte[32])).isFalse();
        assertThat(IdentitySignatures.verify(identity.publicKey(), pin, IdentitySignatures.PIN, wrapped,
                new byte[32])).isTrue();
    }

    @Test
    @DisplayName("refuses a signature by another key, or one cut short or missing")
    void refusesAnythingElse() {
        byte[] signature = identity.signDevice(deviceKey, credential);
        byte[] otherKey = Ed25519TestKeys.generate().publicKey();

        assertThat(IdentitySignatures.verify(otherKey, signature, IdentitySignatures.DEVICE, deviceKey, credential))
                .isFalse();
        assertThat(IdentitySignatures.verify(identity.publicKey(), new byte[10], IdentitySignatures.DEVICE,
                deviceKey, credential)).isFalse();
        assertThat(IdentitySignatures.verify(identity.publicKey(), null, IdentitySignatures.DEVICE, deviceKey,
                credential)).isFalse();
        assertThat(IdentitySignatures.verify(new byte[5], signature, IdentitySignatures.DEVICE, deviceKey,
                credential)).isFalse();
    }

    @Test
    @DisplayName("knows a 32-byte Ed25519 key from anything else")
    void recognisesPublicKeys() {
        assertThat(IdentitySignatures.isPublicKey(identity.publicKey())).isTrue();
        assertThat(IdentitySignatures.isPublicKey(new byte[31])).isFalse();
        assertThat(IdentitySignatures.isPublicKey(null)).isFalse();
    }

    @Test
    @DisplayName("a credential names exactly one account and a 128-bit device handle")
    void credentialsNameTheirAccount() {
        assertThat(IdentitySignatures.isCredentialOf(7, credential)).isTrue();
        assertThat(IdentitySignatures.isCredentialOf(70, credential)).isFalse();
        assertThat(IdentitySignatures.isCredentialOf(7, "u7.short".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(IdentitySignatures.isCredentialOf(7,
                "u7.0123456789ABCDEF0123456789abcdef".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(IdentitySignatures.isCredentialOf(7,
                "u71.0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII))).isFalse();
    }
}
