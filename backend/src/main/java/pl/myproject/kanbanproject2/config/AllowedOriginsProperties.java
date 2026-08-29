package pl.myproject.kanbanproject2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * The browser origins allowed to reach this app, held in one place.
 *
 * <p>Two configurations need the same list: {@code SecurityConfiguration.corsConfigurationSource}
 * for the REST surface, and {@code WebSocketConfig.registerStompEndpoints} for the SockJS
 * handshake. They used to hold a copy each, and the copies had already drifted — the WebSocket
 * one additionally allowed {@code http://kanbanproject.pl} and {@code http://www.kanbanproject.pl},
 * plaintext variants the HTTP side rejected. The stricter list is the one kept: a site served over
 * HTTPS has no reason to accept a plaintext origin, and the drift was not a decision anyone made.
 *
 * <p>Which origins are right is a deployment fact, not a source-code one — a new domain should not
 * need a rebuild — so this binds from configuration, with today's list as the default. Set
 * {@code security.cors.allowed-origins} (or {@code SECURITY_CORS_ALLOWED_ORIGINS}, comma-separated)
 * to replace it.
 *
 * <p>This is the same single-sourcing {@link SpaRoutes} does for the client routes.
 */
@ConfigurationProperties(prefix = "security.cors")
public record AllowedOriginsProperties(

        @DefaultValue({
                "https://kanbanproject.pl",
                "https://www.kanbanproject.pl",
                "http://localhost:5173",
                "http://localhost:3000",
                "http://localhost:5174",
                "http://localhost:8080",
                "http://localhost:80",
                "http://127.0.0.1:8080",
                "http://app:8080"
        })
        List<String> allowedOrigins
) {

    public AllowedOriginsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("security.cors.allowed-origins must not be empty");
        }
        if (allowedOrigins.contains("*")) {
            throw new IllegalArgumentException(
                    "security.cors.allowed-origins cannot be \"*\": these origins are used with credentials");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    /** As {@code setAllowedOrigins} varargs want it. */
    public String[] asArray() {
        return allowedOrigins.toArray(String[]::new);
    }
}
