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
        return Arrays.copyOfRange(encoded, encoded.length - IdentitySignatures.KEY_BYTES, encoded.length);
    }

    public byte[] signDevice(byte[] signatureKey, byte[] credential) {
        return sign("KanbanProject messaging device v1", signatureKey, credential);
    }

    public byte[] signPin(byte[] wrappedPrivate, byte[] verifier) {
        return sign("KanbanProject messaging PIN v1", wrappedPrivate, verifier);
    }

    public byte[] signRecovery(byte[] wrappedPrivate) {
        return sign("KanbanProject messaging recovery v1", wrappedPrivate);
    }

    private byte[] sign(String label, byte[]... parts) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(pair.getPrivate());
            signer.update(label.getBytes(StandardCharsets.US_ASCII));
            for (byte[] part : parts) {
                signer.update(part);
            }
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] credential(Integer userId) {
        return ("u" + userId + ".0123456789abcdef0123456789abcdef").getBytes(StandardCharsets.US_ASCII);
    }
}
