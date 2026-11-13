package pl.myproject.kanbanproject2.config.websocket;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import pl.myproject.kanbanproject2.config.security.ratelimit.ApiRateLimiter;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StompSendRateLimiterTest {

    private static final MessageChannel CHANNEL = mock(MessageChannel.class);
    private static final Integer ACCOUNT = 7;

    private ApiRateLimiter limiter;
    private SimpleMeterRegistry meterRegistry;
    private StompSendRateLimiter interceptor;

    @BeforeEach
    void setUp() {
        limiter = mock(ApiRateLimiter.class);
        meterRegistry = new SimpleMeterRegistry();
        interceptor = new StompSendRateLimiter(limiter, meterRegistry);
    }

    @Test
    @DisplayName("a SEND within the account's budget reaches the handler")
    void aSendWithinBudgetPasses() {
        when(limiter.allowsSocketSend(ACCOUNT)).thenReturn(true);
        var message = frame(StompCommand.SEND, signedIn());

        assertThat(interceptor.preSend(message, CHANNEL)).isSameAs(message);
        assertThat(meterRegistry.find(BoardSubscriptionInterceptor.DROPPED_COUNTER).counters()).isEmpty();
    }

    @Test
    @DisplayName("a SEND over the budget is dropped and counted, so a flood is neither stored nor broadcast")
    void aSendOverBudgetIsDropped() {
        when(limiter.allowsSocketSend(ACCOUNT)).thenReturn(false);

        assertThat(interceptor.preSend(frame(StompCommand.SEND, signedIn()), CHANNEL)).isNull();
        assertThat(meterRegistry.get(BoardSubscriptionInterceptor.DROPPED_COUNTER)
                .tags("reason", "rate-limited").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("frames that are not a SEND spend nothing")
    void otherFramesAreNotCounted() {
        for (StompCommand command : List.of(StompCommand.CONNECT, StompCommand.SUBSCRIBE,
                StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT)) {
            var message = frame(command, signedIn());

            assertThat(interceptor.preSend(message, CHANNEL)).isSameAs(message);
        }
        verifyNoInteractions(limiter);
    }

    @Test
    @DisplayName("a SEND with no principal is left to the handlers, which drop it")
    void anAnonymousSendIsNotCounted() {
        var message = frame(StompCommand.SEND, null);

        assertThat(interceptor.preSend(message, CHANNEL)).isSameAs(message);
        verifyNoInteractions(limiter);
    }

    private static UsernamePasswordAuthenticationToken signedIn() {
        var user = new User("someone", "someone@example.test", "hashed");
        user.setId(ACCOUNT);
        return new UsernamePasswordAuthenticationToken(user, null, List.of());
    }

    private static Message<byte[]> frame(StompCommand command, UsernamePasswordAuthenticationToken principal) {
        var accessor = StompHeaderAccessor.create(command);
        accessor.setDestination("/app/chat.sendMessage");
        accessor.setUser(principal);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
