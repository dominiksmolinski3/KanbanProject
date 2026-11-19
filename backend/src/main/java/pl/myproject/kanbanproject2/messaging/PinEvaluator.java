package pl.myproject.kanbanproject2.messaging;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.KeyAgreement;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class PinEvaluator {

    static final int POINT_BYTES = 65;

    private static final byte[] P256_SPKI_PREFIX =
            HexFormat.of().parseHex("3059301306072a8648ce3d020106082a8648ce3d030107034200");
    private static final byte[] KEY_LABEL = "KanbanProject PIN key v1".getBytes(StandardCharsets.US_ASCII);

    private final PrivateKey key;

    public PinEvaluator(MessagingProperties properties) {
        this.key = StringUtils.hasText(properties.pinKey()) ? derive(properties.pinKey()) : null;
    }

    public boolean isConfigured() {
        return key != null;
    }

    public Optional<byte[]> evaluate(byte[] blinded) {
        if (key == null || blinded == null || blinded.length != POINT_BYTES || blinded[0] != 0x04) {
            return Optional.empty();
        }
        byte[] encoded = new byte[P256_SPKI_PREFIX.length + POINT_BYTES];
        System.arraycopy(P256_SPKI_PREFIX, 0, encoded, 0, P256_SPKI_PREFIX.length);
        System.arraycopy(blinded, 0, encoded, P256_SPKI_PREFIX.length, POINT_BYTES);
        try {
            PublicKey point = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(key);
            agreement.doPhase(point, true);
            return Optional.of(agreement.generateSecret());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static PrivateKey derive(String secret) {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec curve = parameters.getParameterSpec(ECParameterSpec.class);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY_LABEL);
            byte[] hashed = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            BigInteger order = curve.getOrder();
            BigInteger scalar = new BigInteger(1, hashed).mod(order.subtract(BigInteger.ONE)).add(BigInteger.ONE);
            return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(scalar, curve));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available", e);
        }
    }
}
