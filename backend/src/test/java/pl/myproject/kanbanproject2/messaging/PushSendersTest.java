package pl.myproject.kanbanproject2.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.NOW;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.device;
import static pl.myproject.kanbanproject2.messaging.MessagingFixtures.user;

class PushSendersTest {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private HttpServer server;
    private String origin;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(201);
    private final AtomicReference<String> answer = new AtomicReference<>("");

    record Request(String path, java.util.Map<String, String> headers, byte[] body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
            requests.add(new Request(exchange.getRequestURI().getPath(), headers, exchange.getRequestBody().readAllBytes()));
            byte[] out = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                exchange.getResponseBody().write(out);
            }
            exchange.close();
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static KeyPair p256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private PushEndpoint endpoint(PushKind kind, String address, String p256dh, String auth) {
        return new PushEndpoint(device(7, user(1, "Ania")), kind, address, p256dh, auth, NOW);
    }

    @Test
    @DisplayName("a Web Push request is signed for its push service and its payload opens with the receiver's key (RFC 8291)")
    void webPush() throws Exception {
        KeyPair vapid = p256();
        KeyPair receiver = p256();
        byte[] receiverPublic = WebPushSender.uncompressed((ECPublicKey) receiver.getPublic());
        byte[] authSecret = new byte[16];
        new java.security.SecureRandom().nextBytes(authSecret);
        byte[] scalar = ((java.security.interfaces.ECPrivateKey) vapid.getPrivate()).getS().toByteArray();
        scalar = scalar.length > 32 ? Arrays.copyOfRange(scalar, scalar.length - 32, scalar.length) : scalar;
        String publicKey = URL.encodeToString(WebPushSender.uncompressed((ECPublicKey) vapid.getPublic()));
        PushProperties properties = new PushProperties(publicKey, URL.encodeToString(scalar), "mailto:ops@example.com", "");
        WebPushSender sender = new WebPushSender(properties, HttpClient.newHttpClient(), Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        sender.send(endpoint(PushKind.WEBPUSH, origin + "/push/abc", URL.encodeToString(receiverPublic),
                URL.encodeToString(authSecret)), 42, 9);

        assertThat(requests).hasSize(1);
        Request sent = requests.get(0);
        assertThat(sent.path()).isEqualTo("/push/abc");
        assertThat(sent.headers()).containsEntry("content-encoding", "aes128gcm").containsEntry("ttl", "86400");

        String authorization = sent.headers().get("authorization");
        assertThat(authorization).startsWith("vapid t=").endsWith(", k=" + publicKey);
        String jwt = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        JsonNode claims = new ObjectMapper().readTree(URL_DECODER.decode(parts[1]));
        assertThat(claims.get("aud").asText()).isEqualTo(origin);
        assertThat(claims.get("sub").asText()).isEqualTo("mailto:ops@example.com");
        assertThat(claims.get("exp").asLong()).isEqualTo(NOW.plusSeconds(12 * 3600).getEpochSecond());
        Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
        verify.initVerify(vapid.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(URL_DECODER.decode(parts[2]))).isTrue();

        assertThat(new String(decrypt(sent.body(), receiver, receiverPublic, authSecret), StandardCharsets.UTF_8))
                .isEqualTo("{\"c\":42,\"s\":9}");
    }

    private static byte[] decrypt(byte[] body, KeyPair receiver, byte[] receiverPublic, byte[] authSecret) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        assertThat(in.getInt()).isEqualTo(WebPushSender.RECORD_SIZE);
        byte[] senderPublic = new byte[in.get() & 0xff];
        in.get(senderPublic);
        byte[] sealed = new byte[in.remaining()];
        in.get(sealed);
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(receiver.getPrivate());
        agreement.doPhase(WebPushSender.publicKey(senderPublic), true);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), receiverPublic, senderPublic);
        byte[] ikm = WebPushSender.hkdf(authSecret, agreement.generateSecret(), keyInfo, 32);
        byte[] key = WebPushSender.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPushSender.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plain = cipher.doFinal(sealed);
        assertThat(plain[plain.length - 1]).isEqualTo((byte) 2);
        return Arrays.copyOf(plain, plain.length - 1);
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private WebPushSender configuredWebPush() throws Exception {
        KeyPair vapid = p256();
        byte[] scalar = ((java.security.interfaces.ECPrivateKey) vapid.getPrivate()).getS().toByteArray();
        scalar = scalar.length > 32 ? Arrays.copyOfRange(scalar, scalar.length - 32, scalar.length) : scalar;
        return new WebPushSender(new PushProperties(
                URL.encodeToString(WebPushSender.uncompressed((ECPublicKey) vapid.getPublic())),
                URL.encodeToString(scalar), "mailto:ops@example.com", ""), HttpClient.newHttpClient(), Clock.systemUTC());
    }

    @Test
    @DisplayName("a push service that says 404 or 410 means the browser unsubscribed; anything else is a failure to retry")
    void webPushRefusals() throws Exception {
        WebPushSender sender = configuredWebPush();
        KeyPair receiver = p256();
        PushEndpoint target = endpoint(PushKind.WEBPUSH, origin + "/p",
                URL.encodeToString(WebPushSender.uncompressed((ECPublicKey) receiver.getPublic())), URL.encodeToString(new byte[16]));

        status.set(410);
        assertThatThrownBy(() -> sender.send(target, 1, 1)).isInstanceOf(PushSender.PushException.class)
                .satisfies(error -> assertThat(((PushSender.PushException) error).isGone()).isTrue());
        status.set(500);
        assertThatThrownBy(() -> sender.send(target, 1, 1))
                .satisfies(error -> assertThat(((PushSender.PushException) error).isGone()).isFalse());
        assertThatThrownBy(() -> sender.send(endpoint(PushKind.WEBPUSH, origin + "/p", "!!", "!!"), 1, 1))
                .isInstanceOf(PushSender.PushException.class);
    }

    @Test
    @DisplayName("Web Push is configured only with a public key, a private key and a contact")
    void webPushConfigured() {
        assertThat(new WebPushSender(new PushProperties("a", "b", "c", "")).configured()).isTrue();
        assertThat(new WebPushSender(new PushProperties("a", "b", "", "")).configured()).isFalse();
        assertThat(new WebPushSender(new PushProperties("", "", "", "")).kind()).isEqualTo(PushKind.WEBPUSH);
    }

    private String credentials(String tokenUri) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        return new ObjectMapper().writeValueAsString(java.util.Map.of("project_id", "demo-project",
                "client_email", "sender@demo-project.iam.gserviceaccount.com", "private_key", pem, "token_uri", tokenUri));
    }

    @Test
    @DisplayName("FCM gets an access token once, then sends only the ids with a notification text the app translates")
    void fcm() throws Exception {
        answer.set("{\"access_token\":\"tok-1\",\"expires_in\":3600}");
        status.set(200);
        FcmSender sender = new FcmSender(new PushProperties("", "", "", credentials(origin + "/token")),
                HttpClient.newHttpClient(), Clock.fixed(NOW, java.time.ZoneOffset.UTC), origin);
        PushEndpoint target = endpoint(PushKind.FCM, "device-token-1", null, null);

        sender.send(target, 5, 77);
        sender.send(target, 5, 78);

        assertThat(requests.stream().map(Request::path)).containsExactly("/token", "/v1/projects/demo-project/messages:send",
                "/v1/projects/demo-project/messages:send");
        String form = new String(requests.get(0).body(), StandardCharsets.UTF_8);
        assertThat(form).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer").contains("assertion=");
        Request first = requests.get(1);
        assertThat(first.headers()).containsEntry("authorization", "Bearer tok-1");
        JsonNode message = new ObjectMapper().readTree(first.body()).get("message");
        assertThat(message.get("token").asText()).isEqualTo("device-token-1");
        assertThat(message.get("data").get("conversationId").asText()).isEqualTo("5");
        assertThat(message.get("data").get("seq").asText()).isEqualTo("77");
        assertThat(message.get("android").get("notification").get("title_loc_key").asText()).isEqualTo(FcmSender.TITLE_KEY);
        assertThat(message.toString()).doesNotContain("text");
        assertThat(sender.kind()).isEqualTo(PushKind.FCM);
        assertThat(sender.configured()).isTrue();
    }

    @Test
    @DisplayName("an unregistered FCM token is gone, a refused token request and a broken key are failures")
    void fcmRefusals() throws Exception {
        answer.set("{\"access_token\":\"t\",\"expires_in\":3600}");
        status.set(200);
        FcmSender sender = new FcmSender(new PushProperties("", "", "", credentials(origin + "/token")),
                HttpClient.newHttpClient(), Clock.systemUTC(), origin);
        PushEndpoint target = endpoint(PushKind.FCM, "stale", null, null);
        sender.send(target, 1, 1);
        requests.clear();

        // the token is cached, so the next call goes straight to the send endpoint
        status.set(404);
        answer.set("{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}");
        assertThatThrownBy(() -> sender.send(target, 1, 2))
                .satisfies(error -> assertThat(((PushSender.PushException) error).isGone()).isTrue());
        status.set(503);
        answer.set("busy");
        assertThatThrownBy(() -> sender.send(target, 1, 3))
                .satisfies(error -> assertThat(((PushSender.PushException) error).isGone()).isFalse());

        FcmSender denied = new FcmSender(new PushProperties("", "", "", credentials(origin + "/token")),
                HttpClient.newHttpClient(), Clock.systemUTC(), origin);
        status.set(401);
        assertThatThrownBy(() -> denied.send(target, 1, 4)).isInstanceOf(PushSender.PushException.class);
        FcmSender broken = new FcmSender(new PushProperties("", "", "", "not json"), HttpClient.newHttpClient(),
                Clock.systemUTC(), origin);
        assertThatThrownBy(() -> broken.send(target, 1, 5)).isInstanceOf(PushSender.PushException.class);
    }

    @Test
    @DisplayName("an expired FCM token is fetched again")
    void fcmTokenRefresh() throws Exception {
        answer.set("{\"access_token\":\"tok\",\"expires_in\":30}");
        status.set(200);
        FcmSender sender = new FcmSender(new PushProperties("", "", "", credentials(origin + "/token")),
                HttpClient.newHttpClient(), Clock.systemUTC(), origin);
        PushEndpoint target = endpoint(PushKind.FCM, "t", null, null);

        sender.send(target, 1, 1);
        sender.send(target, 1, 2);

        assertThat(requests.stream().filter(request -> request.path().equals("/token"))).hasSize(2);
        assertThat(URI.create(origin).getPort()).isPositive();
        assertThat(ReflectionTestUtils.getField(sender, "base")).isEqualTo(origin);
    }
}
