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

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Order matters: authentication puts the principal on the session that the subscription check reads.
        registration.interceptors(webSocketAuthInterceptor, boardSubscriptionInterceptor);
    }
}