package pl.myproject.kanbanproject2.config.websocket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the real STOMP broker lives. {@link WebSocketConfig} relays every {@code /topic} and
 * {@code /queue} frame to it rather than holding the broker in the JVM - the one piece of in-JVM
 * state the container-split plan left behind, because a board event or a chat message published on
 * one API replica never reached a subscriber connected to another.
 *
 * <p>{@code host} defaults to {@code localhost} the same way {@code AuthRateLimitProperties.redisHost}
 * does, so a fresh clone still starts with no broker running - Spring's relay reconnects in the
 * background rather than failing application startup, the same allowance the rate limiter's fail-open
 * makes for an unreachable Redis, just built into the framework here rather than into this repo's own
 * code. docker-compose and the deployment each set their own {@code host}; {@code username}/
 * {@code password} default to RabbitMQ's own stock account, which only ever answers on this network.
 */
@ConfigurationProperties(prefix = "stomp.relay")
public record StompRelayProperties(

        @DefaultValue("localhost") String host,
        @DefaultValue("61613") int port,

        /*
         * One account, used both as the client login (every proxied browser frame rides it, since
         * the browser already authenticated at the WebSocket handshake - see WebSocketAuthInterceptor)
         * and the system login (Spring's own connection for broker-internal frames). Splitting them
         * would buy nothing: both logins reach the same vhost with the same rights, and RabbitMQ has
         * no notion of a caller identity past the TCP connection its STOMP plugin terminates.
         */
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
