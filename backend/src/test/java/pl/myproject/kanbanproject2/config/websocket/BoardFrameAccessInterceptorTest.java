package pl.myproject.kanbanproject2.config.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import pl.myproject.kanbanproject2.board.BoardService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BoardFrameAccessInterceptorTest {

    private static final MessageChannel CHANNEL = mock(MessageChannel.class);
    private static final Instant START = Instant.parse("2026-10-20T10:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private BoardService boardService;
    private StompSessionUsers sessionUsers;
    private BoardFrameAccessInterceptor interceptor;

    @BeforeEach
    void setUp() {
        boardService = mock(BoardService.class);
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        sessionUsers = new StompSessionUsers(clock);
        interceptor = new BoardFrameAccessInterceptor(sessionUsers, boardService);
        sessionUsers.register("s1", 4);
    }

    @Test
    @DisplayName("a member still on the board gets its frames")
    void passesFramesToAMember() {
        when(boardService.isVisibleTo(4, 7)).thenReturn(true);
        var frame = frame("s1", "/topic/boards.7.chat");

        assertThat(interceptor.preSend(frame, CHANNEL)).isSameAs(frame);
    }

    @Test
    @DisplayName("a subscription made while a member stops receiving once the member is removed")
    void withholdsFramesOnceAccessIsLost() {
        when(boardService.isVisibleTo(4, 7)).thenReturn(true, false);

        assertThat(interceptor.preSend(frame("s1", "/topic/boards.7.chat"), CHANNEL)).isNotNull();
        now.set(START.plus(StompSessionUsers.VISIBILITY_TTL).plusMillis(1));

        assertThat(interceptor.preSend(frame("s1", "/topic/boards.7.chat"), CHANNEL))
                .as("the broker still holds the subscription, so only this check keeps the chat from a "
                        + "removed member")
                .isNull();
        assertThat(interceptor.preSend(frame("s1", "/topic/boards.7"), CHANNEL)).isNull();
    }

    @Test
    @DisplayName("the answer is reused for a few seconds rather than asked of the database per frame")
    void cachesTheAnswerBriefly() {
        when(boardService.isVisibleTo(4, 7)).thenReturn(true);

        for (int i = 0; i < 20; i++) {
            interceptor.preSend(frame("s1", "/topic/boards.7"), CHANNEL);
        }
        now.set(START.plus(Duration.ofSeconds(4)));
        interceptor.preSend(frame("s1", "/topic/boards.7.chat"), CHANNEL);
        verify(boardService, times(1)).isVisibleTo(4, 7);

        now.set(START.plus(StompSessionUsers.VISIBILITY_TTL).plusMillis(1));
        interceptor.preSend(frame("s1", "/topic/boards.7"), CHANNEL);
        verify(boardService, times(2)).isVisibleTo(4, 7);
    }

    @Test
    @DisplayName("direct messages, errors and frames that are not MESSAGEs are not this check's business")
    void leavesOtherFramesAlone() {
        var direct = frame("s1", "/user/queue/messages");
        var connected = MessageBuilder.createMessage(new byte[0], headers(SimpMessageType.CONNECT_ACK, "s1", null));

        assertThat(interceptor.preSend(direct, CHANNEL)).isSameAs(direct);
        assertThat(interceptor.preSend(connected, CHANNEL)).isSameAs(connected);
        verifyNoInteractions(boardService);
    }

    @Test
    @DisplayName("a disconnected session is forgotten, with its cached answers")
    void forgetsADisconnectedSession() {
        sessionUsers.onDisconnect(new SessionDisconnectEvent(this,
                MessageBuilder.createMessage(new byte[0], headers(SimpMessageType.DISCONNECT, "s1", null)),
                "s1", CloseStatus.NORMAL));

        assertThat(sessionUsers.userOf("s1")).isNull();
        assertThat(sessionUsers.size()).isZero();
    }

    private static Message<byte[]> frame(String sessionId, String destination) {
        return MessageBuilder.createMessage(new byte[0], headers(SimpMessageType.MESSAGE, sessionId, destination));
    }

    private static org.springframework.messaging.MessageHeaders headers(SimpMessageType type, String sessionId,
                                                                         String destination) {
        var accessor = SimpMessageHeaderAccessor.create(type);
        accessor.setSessionId(sessionId);
        accessor.setDestination(destination);
        return accessor.getMessageHeaders();
    }
}
