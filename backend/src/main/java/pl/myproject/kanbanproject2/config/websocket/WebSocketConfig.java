package pl.myproject.kanbanproject2.config.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import pl.myproject.kanbanproject2.config.AllowedOriginsProperties;

@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties({AllowedOriginsProperties.class, StompRelayProperties.class})
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final BoardSubscriptionInterceptor boardSubscriptionInterceptor;
    private final AllowedOriginsProperties allowedOrigins;
    private final StompRelayProperties stompRelay;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins.asArray())
                .withSockJS();
    }

    /**
     * A real broker rather than {@code enableSimpleBroker}, which held every subscription in this
     * JVM's own memory - a board event or a chat message published on one API replica never reached
     * a subscriber connected to another, and nothing errored to say so. Every replica now relays to
     * the same external broker instead, the same shape moving AuthRateLimiter's escalation to Redis
     * already used: the shared state lives where every replica can see it, not in the process that
     * happens to have handled a given request.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {

        registry.enableStompBrokerRelay("/topic", "/queue")
                .setRelayHost(stompRelay.host())
                .setRelayPort(stompRelay.port())
                .setClientLogin(stompRelay.username())
                .setClientPasscode(stompRelay.password())
                .setSystemLogin(stompRelay.username())
                .setSystemPasscode(stompRelay.password());
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    /**
     * Order is load-bearing: the authentication interceptor is what puts the principal on the
     * session, so a subscription check running ahead of it would read every SUBSCRIBE frame as
     * anonymous and refuse the lot.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor, boardSubscriptionInterceptor);
    }
}