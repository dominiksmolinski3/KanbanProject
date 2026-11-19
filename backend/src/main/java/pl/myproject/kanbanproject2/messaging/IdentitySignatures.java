package pl.myproject.kanbanproject2.messaging;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.regex.Pattern;

final class DeviceSignatures {

    // Must match DEVICE_SIGNATURE_LABEL in frontend/src/crypto/keys.ts, byte for byte.
    static final byte[] LABEL = "KanbanProject messaging device v1".getBytes(StandardCharsets.US_ASCII);

    static final int KEY_BYTES = 32;
    static final int SIGNATURE_BYTES = 64;

    private static final byte[] ED25519_SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final Pattern DEVICE_HANDLE = Pattern.compile("[0-9a-f]{32}");

    private DeviceSignatures() {
    }

    static boolean isPublicKey(byte[] raw) {
        return publicKey(raw) != null;
    }

    static boolean verify(byte[] identityKey, byte[] signatureKey, byte[] credential, byte[] signature) {
        PublicKey key = publicKey(identityKey);
        if (key == null || signature == null || signature.length != SIGNATURE_BYTES) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(LABEL);
            verifier.update(signatureKey);
            verifier.update(credential);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static boolean isCredentialOf(Integer userId, byte[] credential) {
        String text = new String(credential, StandardCharsets.US_ASCII);
        String prefix = "u" + userId + ".";
        return text.startsWith(prefix) && DEVICE_HANDLE.matcher(text.substring(prefix.length())).matches();
    }

    private static PublicKey publicKey(byte[] raw) {
        if (raw == null || raw.length != KEY_BYTES) {
            return null;
        }
        byte[] encoded = new byte[ED25519_SPKI_PREFIX.length + KEY_BYTES];
        System.arraycopy(ED25519_SPKI_PREFIX, 0, encoded, 0, ED25519_SPKI_PREFIX.length);
        System.arraycopy(raw, 0, encoded, ED25519_SPKI_PREFIX.length, KEY_BYTES);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
