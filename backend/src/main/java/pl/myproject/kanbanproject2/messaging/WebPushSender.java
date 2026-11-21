package pl.myproject.kanbanproject2.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

@Component
public class WebPushSender implements PushSender {

    static final int RECORD_SIZE = 4096;

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final PushProperties properties;
    private final HttpClient http;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public WebPushSender(PushProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Clock.systemUTC());
    }

    WebPushSender(PushProperties properties, HttpClient http, Clock clock) {
        this.properties = properties;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public PushKind kind() {
        return PushKind.WEBPUSH;
    }

    @Override
    public boolean configured() {
        return properties.webPushConfigured();
    }

    @Override
    public void send(PushEndpoint endpoint, long conversationId, long seq) throws PushException {
        try {
            byte[] body = encrypt(("{\"c\":" + conversationId + ",\"s\":" + seq + "}").getBytes(StandardCharsets.UTF_8),
                    URL_DECODER.decode(endpoint.getP256dh()), URL_DECODER.decode(endpoint.getAuth()));
            URI target = URI.create(endpoint.getEndpoint());
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "vapid t=" + vapidToken(target) + ", k=" + properties.vapidPublicKey())
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", "86400")
                    .header("Urgency", "normal")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new PushException("Web Push answered " + status, null, status == 404 || status == 410);
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new PushException("Web Push could not be sent: " + e.getMessage(), e, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PushException("Web Push was interrupted", e, false);
        }
    }

    String vapidToken(URI target) throws GeneralSecurityException {
        String audience = target.getScheme() + "://" + target.getAuthority();
        long expires = clock.instant().plusSeconds(12 * 3600).getEpochSecond();
        String signing = URL.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + URL.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + expires + ",\"sub\":\""
                + properties.vapidSubject().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(vapidPrivateKey());
        signature.update(signing.getBytes(StandardCharsets.US_ASCII));
        return signing + "." + URL.encodeToString(signature.sign());
    }

    private PrivateKey vapidPrivateKey() throws GeneralSecurityException {
        BigInteger scalar = new BigInteger(1, URL_DECODER.decode(properties.vapidPrivateKey()));
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(scalar, p256()));
    }

    static ECParameterSpec p256() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    static PublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) {
            throw new GeneralSecurityException("Not an uncompressed P-256 point");
        }
        ECPoint point = new ECPoint(new BigInteger(1, java.util.Arrays.copyOfRange(uncompressed, 1, 33)),
                new BigInteger(1, java.util.Arrays.copyOfRange(uncompressed, 33, 65)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, p256()));
    }

    static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 4;
        copy(key.getW().getAffineX(), out, 1);
        copy(key.getW().getAffineY(), out, 33);
        return out;
    }

    private static void copy(BigInteger value, byte[] target, int offset) {
        byte[] raw = value.toByteArray();
        int from = Math.max(0, raw.length - 32);
        System.arraycopy(raw, from, target, offset + 32 - (raw.length - from), raw.length - from);
    }

    byte[] encrypt(byte[] plain, byte[] receiverPublic, byte[] authSecret) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair ephemeral = generator.generateKeyPair();
        byte[] senderPublic = uncompressed((ECPublicKey) ephemeral.getPublic());
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate());
        agreement.doPhase(publicKey(receiverPublic), true);
        byte[] shared = agreement.generateSecret();
        byte[] salt = new byte[16];
        random.nextBytes(salt);

        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), receiverPublic, senderPublic);
        byte[] ikm = hkdf(authSecret, shared, keyInfo, 32);
        byte[] contentKey = hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(contentKey, "AES"), new GCMParameterSpec(128, nonce));
        byte[] sealed = cipher.doFinal(concat(plain, new byte[]{2}));

        ByteBuffer out = ByteBuffer.allocate(16 + 4 + 1 + senderPublic.length + sealed.length);
        out.put(salt).putInt(RECORD_SIZE).put((byte) senderPublic.length).put(senderPublic).put(sealed);
        return out.array();
    }

    static byte[] hkdf(byte[] salt, byte[] input, byte[] info, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(input);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return java.util.Arrays.copyOf(mac.doFinal(), length);
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] out = new byte[size];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
