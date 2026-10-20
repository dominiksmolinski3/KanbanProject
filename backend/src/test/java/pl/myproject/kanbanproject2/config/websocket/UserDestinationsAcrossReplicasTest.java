package pl.myproject.kanbanproject2.config.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.config.StompBrokerRelayRegistration;
import pl.myproject.kanbanproject2.config.AllowedOriginsProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserDestinationsAcrossReplicasTest {

    @Test
    @DisplayName("a message to a user is broadcast to every replica, since the user's socket may be on any of them")
    void userDestinationsAreBroadcast() {
        var registry = mock(MessageBrokerRegistry.class);
        var relay = mock(StompBrokerRelayRegistration.class, RETURNS_SELF);
        when(registry.enableStompBrokerRelay(anyString(), anyString())).thenReturn(relay);

        config().configureMessageBroker(registry);

        verify(relay).setUserDestinationBroadcast(WebSocketConfig.UNRESOLVED_USER_DESTINATION);
        verify(relay).setUserRegistryBroadcast(WebSocketConfig.USER_REGISTRY_BROADCAST);
    }

    @Test
    @DisplayName("both broadcast topics are destinations RabbitMQ accepts: nothing after /topic/ but one routing key")
    void theTopicsAreValidForRabbitMq() {
        for (String destination : List.of(WebSocketConfig.UNRESOLVED_USER_DESTINATION, WebSocketConfig.USER_REGISTRY_BROADCAST)) {
            assertThat(destination).startsWith("/topic/");
            assertThat(destination.substring("/topic/".length())).doesNotContain("/");
        }
    }

    @Test
    @DisplayName("no client may subscribe to them: the registry topic lists who is online")
    void clientsCannotListenIn() {
        assertThat(StompDestinationPolicy.allowsSubscribe(WebSocketConfig.USER_REGISTRY_BROADCAST)).isFalse();
        assertThat(StompDestinationPolicy.allowsSubscribe(WebSocketConfig.UNRESOLVED_USER_DESTINATION)).isFalse();
    }

    private static WebSocketConfig config() {
        return new WebSocketConfig(mock(WebSocketAuthInterceptor.class), mock(StompDestinationPolicy.class),
                mock(BoardSubscriptionInterceptor.class), mock(BoardFrameAccessInterceptor.class),
                mock(SignedOutSessionInterceptor.class), new AllowedOriginsProperties(List.of("http://localhost:5173")),
                new StompRelayProperties("localhost", 61613, "guest", "guest"));
    }
}
