package pl.myproject.kanbanproject2.config.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "stomp.relay")
public record StompRelayProperties(

        @DefaultValue("localhost") String host,
        @DefaultValue("61613") int port,

        @DefaultValue("guest") String username,
        @DefaultValue("guest") String password) {

    public StompRelayProperties {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("stomp.relay.host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("stomp.relay.port must be a valid port number");
        }
    }
}
