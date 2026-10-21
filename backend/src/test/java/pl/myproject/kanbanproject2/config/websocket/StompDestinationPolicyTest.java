package pl.myproject.kanbanproject2.config.websocket;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import pl.myproject.kanbanproject2.user.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StompDestinationPolicyTest {

    private static final MessageChannel CHANNEL = mock(MessageChannel.class);

    private SimpleMeterRegistry meterRegistry;
    private StompDestinationPolicy policy;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        policy = new StompDestinationPolicy(meterRegistry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/app/chat.sendMessage", "/app/chat.sendPrivateMessage", "/app/chat.join", "/app/chat.leave"})
    @DisplayName("a SEND to a @MessageMapping handler reaches the controller that checks the caller")
    void letsApplicationSendsThrough(String destination) {
        var message = frame(StompCommand.SEND, destination);

        assertThat(policy.preSend(message, CHANNEL)).isSameAs(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/boards.7.chat",
            "/topic/boards.7",
            "/user/someone@example.test/queue/messages",
            "/queue/messages-user1a2b3c",
            "/topic/#",
            "/exchange/amq.topic/boards.7.chat",
            "/app"})
    @DisplayName("a SEND straight to a broker or user destination is dropped: it would skip every check the controllers make")
    void dropsSendsThatBypassTheControllers(String destination) {
        assertThat(policy.preSend(frame(StompCommand.SEND, destination), CHANNEL))
                .as("%s reached the broker, so any signed-in account can post there under any sender", destination)
                .isNull();
        assertThat(dropped("unlisted-send")).isEqualTo(1.0);
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("a SEND naming no destination is dropped")
    void dropsASendWithNoDestination(String destination) {
        assertThat(policy.preSend(frame(StompCommand.SEND, destination), CHANNEL)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/topic/boards.7", "/topic/boards.7.chat", "/user/queue/messages", "/user/queue/errors",
            "/user/queue/boards"})
    @DisplayName("the destinations the client subscribes to pass on to the board check")
    void letsTheClientsSubscriptionsThrough(String destination) {
        var message = frame(StompCommand.SUBSCRIBE, destination);

        assertThat(policy.preSend(message, CHANNEL)).isSameAs(message);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/#",
            "/topic/*.*.chat",
            "/topic/boards.*",
            "/topic/boards.*.chat",
            "/topic/boards.7.#",
            "/topic/boards.7.chat.extra",
            "/topic/boards.",
            "/topic/public",
            "/queue/messages-user1a2b3c",
            "/user/someone@example.test/queue/messages",
            "/exchange/amq.topic/#"})
    @DisplayName("a wildcard or unlisted SUBSCRIBE is dropped: RabbitMQ would bind it across every board")
    void dropsUnlistedSubscriptions(String destination) {
        assertThat(policy.preSend(frame(StompCommand.SUBSCRIBE, destination), CHANNEL))
                .as("%s reached the broker, which matches topic wildcards against every board's routing key",
                        destination)
                .isNull();
        assertThat(dropped("unlisted-subscribe")).isEqualTo(1.0);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECTED", "MESSAGE", "RECEIPT", "ERROR"})
    @DisplayName("a command only a server sends is dropped: Spring routes a client MESSAGE to the controllers like a SEND")
    void dropsServerCommandsFromAClient(StompCommand command) {
        for (String destination : new String[] {"/app/chat.sendPrivateMessage", "/user/someone@example.test/queue/messages"}) {
            assertThat(policy.preSend(frame(command, destination), CHANNEL))
                    .as("a client %s to %s passed the policy, skipping every check keyed on SEND", command, destination)
                    .isNull();
        }
        assertThat(dropped("server-command")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("frames that name no destination of their own pass through")
    void leavesOtherCommandsAlone() {
        for (StompCommand command : new StompCommand[] {
                StompCommand.CONNECT, StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT, StompCommand.ACK}) {
            var message = frame(command, "/topic/#");

            assertThat(policy.preSend(message, CHANNEL)).isSameAs(message);
        }
        assertThat(meterRegistry.find(BoardSubscriptionInterceptor.DROPPED_COUNTER).counters()).isEmpty();
    }

    private double dropped(String reason) {
        return meterRegistry.get(BoardSubscriptionInterceptor.DROPPED_COUNTER)
                .tags("reason", reason).counter().count();
    }

    private static Message<byte[]> frame(StompCommand command, String destination) {
        var accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        accessor.setUser(authenticated());
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static UsernamePasswordAuthenticationToken authenticated() {
        var user = new User();
        user.setId(1);
        user.setEmail("member@kanban.local");
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }
}
