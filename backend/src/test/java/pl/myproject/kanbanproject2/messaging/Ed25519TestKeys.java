package pl.myproject.kanbanproject2.messaging;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;

public record Ed25519TestKeys(KeyPair pair) {

    public static Ed25519TestKeys generate() {
        try {
            return new Ed25519TestKeys(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] publicKey() {
        byte[] encoded = pair.getPublic().getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - DeviceSignatures.KEY_BYTES, encoded.length);
    }

    public byte[] signDevice(byte[] signatureKey, byte[] credential) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update("KanbanProject messaging device v1".getBytes(StandardCharsets.US_ASCII));
            signer.update(signatureKey);
            signer.update(credential);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] credential(Integer userId) {
        return ("u" + userId + ".0123456789abcdef0123456789abcdef").getBytes(StandardCharsets.US_ASCII);
    }
}
