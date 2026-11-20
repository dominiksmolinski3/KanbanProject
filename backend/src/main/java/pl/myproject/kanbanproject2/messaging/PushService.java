package pl.myproject.kanbanproject2.messaging;

import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.net.URI;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
public class PushService {

    // A browser hands over the URL a push service gave it; only these services are ever called, never an address a user picks.
    static final List<String> PUSH_SERVICES = List.of("fcm.googleapis.com", "android.googleapis.com",
            "push.services.mozilla.com", "push.apple.com", "notify.windows.com");

    static final int MAX_TOKEN_LENGTH = 4096;

    private final ConversationAccess access;
    private final PushEndpointRepository endpoints;
    private final PushProperties properties;
    private final Clock clock;

    @Autowired
    public PushService(ConversationAccess access, PushEndpointRepository endpoints, PushProperties properties) {
        this(access, endpoints, properties, Clock.systemUTC());
    }

    PushService(ConversationAccess access, PushEndpointRepository endpoints, PushProperties properties, Clock clock) {
        this.access = access;
        this.endpoints = endpoints;
        this.properties = properties;
        this.clock = clock;
    }

    public PushConfigDto config() {
        access.requireEnabled();
        return new PushConfigDto(properties.webPushConfigured() ? properties.vapidPublicKey() : null,
                properties.fcmConfigured());
    }

    @Transactional
    public PushEndpointDto register(User caller, PushEndpointRequest request) {
        access.requireEnabled();
        CryptoDevice device = access.requireOwnDevice(caller, request.deviceId());
        validate(request);
        PushEndpoint endpoint = endpoints.findFor(device.getId(), request.kind()).orElse(null);
        if (endpoint == null) {
            endpoint = new PushEndpoint(device, request.kind(), request.endpoint(), request.p256dh(), request.auth(),
                    clock.instant());
        } else {
            endpoint.replace(request.endpoint(), request.p256dh(), request.auth());
        }
        return new PushEndpointDto(endpoints.save(endpoint).getId());
    }

    @Transactional
    public void remove(User caller, Long id) {
        access.requireEnabled();
        endpoints.deleteOwn(id, caller.getId());
    }

    private void validate(PushEndpointRequest request) {
        boolean configured = request.kind() == PushKind.WEBPUSH ? properties.webPushConfigured()
                : properties.fcmConfigured();
        if (!configured) {
            throw new GlobalException(ExceptionIdentifier.PUSH_NOT_CONFIGURED,
                    "Powiadomienia tego rodzaju nie są skonfigurowane na serwerze");
        }
        String endpoint = request.endpoint();
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_TOKEN_LENGTH) {
            throw invalid();
        }
        if (request.kind() == PushKind.FCM) {
            return;
        }
        if (!isPushService(endpoint) || !decodesTo(request.p256dh(), 65) || !decodesTo(request.auth(), 16)) {
            throw invalid();
        }
    }

    static boolean isPushService(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && PUSH_SERVICES.stream().anyMatch(service -> host.equals(service) || host.endsWith("." + service));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean decodesTo(String value, int bytes) {
        try {
            return value != null && Base64.getUrlDecoder().decode(value).length == bytes;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static GlobalException invalid() {
        return new GlobalException(ExceptionIdentifier.INVALID_PUSH_ENDPOINT, "Nieprawidłowy adres powiadomień");
    }
}
