package pl.myproject.kanbanproject2.config.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Locale;
import java.util.Set;

@ConfigurationProperties(prefix = "stomp.relay")
public record StompRelayProperties(

        @DefaultValue("localhost") String host,
        @DefaultValue("61613") int port,

        @DefaultValue("guest") String username,
        @DefaultValue("guest") String password) {

    private static final String GUEST = "guest";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    public StompRelayProperties {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("stomp.relay.host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("stomp.relay.port must be a valid port number");
        }
        if (GUEST.equals(username) && !LOOPBACK_HOSTS.contains(host.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("stomp.relay.username is RabbitMQ's guest user, which the "
                    + "broker accepts from loopback only, so a relay to " + host + " can never log in: "
                    + "set STOMP_RELAY_USERNAME and STOMP_RELAY_PASSWORD");
        }
    }
}
