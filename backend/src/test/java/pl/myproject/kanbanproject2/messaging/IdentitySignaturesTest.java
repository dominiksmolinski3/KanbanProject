package pl.myproject.kanbanproject2.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceSignaturesTest {

    private final Ed25519TestKeys identity = Ed25519TestKeys.generate();
    private final byte[] deviceKey = Ed25519TestKeys.generate().publicKey();
    private final byte[] credential = Ed25519TestKeys.credential(7);

    @Test
    @DisplayName("accepts the identity's signature over the label, the device key and the credential")
    void acceptsTheIdentitySignature() {
        byte[] signature = identity.signDevice(deviceKey, credential);

        assertThat(DeviceSignatures.verify(identity.publicKey(), deviceKey, credential, signature)).isTrue();
    }

    @Test
    @DisplayName("refuses a signature by another key, over another device key, or cut short")
    void refusesAnythingElse() {
        byte[] signature = identity.signDevice(deviceKey, credential);
        byte[] otherKey = Ed25519TestKeys.generate().publicKey();

        assertThat(DeviceSignatures.verify(otherKey, deviceKey, credential, signature)).isFalse();
        assertThat(DeviceSignatures.verify(identity.publicKey(), otherKey, credential, signature)).isFalse();
        assertThat(DeviceSignatures.verify(identity.publicKey(), deviceKey, Ed25519TestKeys.credential(8), signature))
                .isFalse();
        assertThat(DeviceSignatures.verify(identity.publicKey(), deviceKey, credential, new byte[10])).isFalse();
        assertThat(DeviceSignatures.verify(identity.publicKey(), deviceKey, credential, null)).isFalse();
        assertThat(DeviceSignatures.verify(new byte[5], deviceKey, credential, signature)).isFalse();
    }

    @Test
    @DisplayName("knows a 32-byte Ed25519 key from anything else")
    void recognisesPublicKeys() {
        assertThat(DeviceSignatures.isPublicKey(identity.publicKey())).isTrue();
        assertThat(DeviceSignatures.isPublicKey(new byte[31])).isFalse();
        assertThat(DeviceSignatures.isPublicKey(null)).isFalse();
    }

    @Test
    @DisplayName("a credential names exactly one account and a 128-bit device handle")
    void credentialsNameTheirAccount() {
        assertThat(DeviceSignatures.isCredentialOf(7, credential)).isTrue();
        assertThat(DeviceSignatures.isCredentialOf(70, credential)).isFalse();
        assertThat(DeviceSignatures.isCredentialOf(7, "u7.short".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(DeviceSignatures.isCredentialOf(7,
                "u7.0123456789ABCDEF0123456789abcdef".getBytes(StandardCharsets.US_ASCII))).isFalse();
        assertThat(DeviceSignatures.isCredentialOf(7,
                "u71.0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII))).isFalse();
    }
}
