package pl.myproject.kanbanproject2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * The browser origins allowed to reach this app, single-sourced because
 * {@code SecurityConfiguration.corsConfigurationSource} and {@code WebSocketConfig}'s SockJS
 * handshake used to hold a copy each and had drifted (the WebSocket list also allowed plaintext
 * {@code http://kanbanproject.pl} variants). Binds from {@code security.cors.allowed-origins}
 * ({@code SECURITY_CORS_ALLOWED_ORIGINS}, comma-separated) so a new origin is a deployment change,
 * not a rebuild — the same single-sourcing {@link SpaRoutes} does for client routes.
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
