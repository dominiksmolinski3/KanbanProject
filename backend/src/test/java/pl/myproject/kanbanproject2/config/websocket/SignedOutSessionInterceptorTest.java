package pl.myproject.kanbanproject2.config.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetailsService;
import pl.myproject.kanbanproject2.config.security.JwtService;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignedOutSessionInterceptorTest {

    private static final MessageChannel CHANNEL = mock(MessageChannel.class);
    private static final Instant CONNECTED_AT = Instant.parse("2026-10-20T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(CONNECTED_AT.plusSeconds(60));
    private UserRepository users;
    private StompSessionUsers sessionUsers;
    private SignedOutSessionInterceptor outbound;
    private WebSocketAuthInterceptor inbound;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        sessionUsers = new StompSessionUsers(users, clock);
        outbound = new SignedOutSessionInterceptor(sessionUsers);
        inbound = new WebSocketAuthInterceptor(mock(JwtService.class), mock(UserDetailsService.class), sessionUsers);
        sessionUsers.register("s1", 4, CONNECTED_AT);
    }

    @Test
    @DisplayName("a session whose account is still signed in gets its frames")
    void passesFramesWhileSignedIn() {
        when(users.findSessionsRevokedAt(4)).thenReturn(Optional.empty());
        var frame = frame("/user/queue/messages");

        assertThat(outbound.preSend(frame, CHANNEL)).isSameAs(frame);
    }

    @Test
    @DisplayName("signing out everywhere turns the next frame into an ERROR, which closes the socket")
    void closesASignedOutSession() {
        when(users.findSessionsRevokedAt(4)).thenReturn(Optional.of(CONNECTED_AT.plusSeconds(30)));

        Message<?> sent = outbound.preSend(frame("/topic/boards.7.chat"), CHANNEL);

        assertThat(sent).isNotNull();
        var accessor = StompHeaderAccessor.wrap(sent);
        assertThat(accessor.getCommand())
                .as("a dropped frame would leave the socket open, receiving the next one")
                .isEqualTo(StompCommand.ERROR);
        assertThat(accessor.getSessionId()).isEqualTo("s1");
    }

    @Test
    @DisplayName("a revocation older than the connection leaves it open")
    void anOlderRevocationLeavesTheSessionAlone() {
        when(users.findSessionsRevokedAt(4)).thenReturn(Optional.of(CONNECTED_AT.minusSeconds(30)));
        var frame = frame("/topic/boards.7");

        assertThat(outbound.preSend(frame, CHANNEL)).isSameAs(frame);
    }

    @Test
    @DisplayName("the account is asked again only every few seconds, and once signed out stays signed out")
    void cachesTheAnswer() {
        when(users.findSessionsRevokedAt(4)).thenReturn(Optional.empty(), Optional.of(CONNECTED_AT.plusSeconds(1)));

        for (int i = 0; i < 10; i++) {
            outbound.preSend(frame("/topic/boards.7"), CHANNEL);
        }
        verify(users, times(1)).findSessionsRevokedAt(4);

        now.set(now.get().plus(StompSessionUsers.VISIBILITY_TTL).plus(Duration.ofMillis(1)));
        assertThat(sessionUsers.isSignedOut("s1")).isTrue();
        now.set(now.get().plusSeconds(60));
        assertThat(sessionUsers.isSignedOut("s1")).isTrue();
        verify(users, times(2)).findSessionsRevokedAt(4);
    }

    @Test
    @DisplayName("a signed-out session cannot send or subscribe, and the refusal closes it")
    void refusesFramesFromASignedOutSession() {
        when(users.findSessionsRevokedAt(4)).thenReturn(Optional.of(CONNECTED_AT.plusSeconds(30)));

        for (StompCommand command : new StompCommand[] {StompCommand.SEND, StompCommand.SUBSCRIBE}) {
            assertThatThrownBy(() -> inbound.preSend(inboundFrame(command), CHANNEL))
                    .as("%s from a revoked session reached the broker", command)
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatCode(() -> inbound.preSend(inboundFrame(StompCommand.DISCONNECT), CHANNEL))
                .doesNotThrowAnyException();
    }

    private static Message<byte[]> frame(String destination) {
        var accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId("s1");
        accessor.setDestination(destination);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> inboundFrame(StompCommand command) {
        var accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("s1");
        accessor.setDestination("/app/chat.sendMessage");
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
