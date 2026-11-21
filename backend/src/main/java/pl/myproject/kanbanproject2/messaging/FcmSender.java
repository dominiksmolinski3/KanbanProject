package pl.myproject.kanbanproject2.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Component
public class FcmSender implements PushSender {

    static final String DEFAULT_BASE = "https://fcm.googleapis.com";

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    static final String TITLE_KEY = "push_new_message_title";

    static final String BODY_KEY = "push_new_message_body";

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final PushProperties properties;
    private final HttpClient http;
    private final Clock clock;
    private final String base;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode credentials;
    private String accessToken;
    private Instant accessTokenExpires = Instant.EPOCH;

    @Autowired
    public FcmSender(PushProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), Clock.systemUTC(),
                DEFAULT_BASE);
    }

    FcmSender(PushProperties properties, HttpClient http, Clock clock, String base) {
        this.properties = properties;
        this.http = http;
        this.clock = clock;
        this.base = base;
    }

    @Override
    public PushKind kind() {
        return PushKind.FCM;
    }

    @Override
    public boolean configured() {
        return properties.fcmConfigured();
    }

    @Override
    public void send(PushEndpoint endpoint, long conversationId, long seq) throws PushException {
        try {
            JsonNode account = account();
            Map<String, Object> message = Map.of("message", Map.of(
                    "token", endpoint.getEndpoint(),
                    "data", Map.of("conversationId", String.valueOf(conversationId), "seq", String.valueOf(seq)),
                    "android", Map.of("priority", "HIGH", "notification", Map.of(
                            "title_loc_key", TITLE_KEY, "body_loc_key", BODY_KEY))));
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(base + "/v1/projects/" + account.get("project_id").asText() + "/messages:send"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token(account))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                boolean gone = status == 404 || response.body().contains("UNREGISTERED");
                throw new PushException("FCM answered " + status, null, gone);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            throw new PushException("FCM could not be sent: " + e.getMessage(), e, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PushException("FCM was interrupted", e, false);
        }
    }

    private synchronized JsonNode account() throws IOException {
        if (credentials == null) {
            credentials = json.readTree(properties.fcmCredentials());
        }
        return credentials;
    }

    private synchronized String token(JsonNode account) throws IOException, InterruptedException, GeneralSecurityException {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(accessTokenExpires.minusSeconds(60))) {
            return accessToken;
        }
        String tokenUri = account.get("token_uri").asText();
        String signing = URL.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + URL.encodeToString(json.writeValueAsBytes(Map.of(
                "iss", account.get("client_email").asText(), "scope", SCOPE, "aud", tokenUri,
                "iat", now.getEpochSecond(), "exp", now.plusSeconds(3600).getEpochSecond())));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey(account.get("private_key").asText()));
        signature.update(signing.getBytes(StandardCharsets.US_ASCII));
        String assertion = signing + "." + URL.encodeToString(signature.sign());
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=" + URLEncoder.encode(
                        "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                        + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Google answered " + response.statusCode() + " to the token request");
        }
        JsonNode answer = json.readTree(response.body());
        accessToken = answer.get("access_token").asText();
        accessTokenExpires = now.plusSeconds(answer.path("expires_in").asLong(3600));
        return accessToken;
    }

    private static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }
}
