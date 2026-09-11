package pl.myproject.kanbanproject2.config.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The simple broker does not authorise a destination, so this is the whole of what stops a
 * subscriber holding any valid token from sitting on any board's topic.
 */
@ExtendWith(MockitoExtension.class)
class BoardSubscriptionInterceptorTest {

    private static final MessageChannel CHANNEL = mock(MessageChannel.class);

    @Mock
    private BoardService boardService;

    @InjectMocks
    private BoardSubscriptionInterceptor interceptor;

    private final User caller = caller();

    @Test
    @DisplayName("a member may subscribe to the board")
    void letsAMemberThrough() {
        var message = subscribe("/topic/boards/7", authenticated());

        assertThat(interceptor.preSend(message, CHANNEL)).isSameAs(message);
        verify(boardService).requireVisible(eq(caller), eq(7));
    }

    @Test
    @DisplayName("somebody else's board is refused, on the same check the REST route uses")
    void refusesABoardTheCallerCannotSee() {
        when(boardService.requireVisible(any(User.class), any(Integer.class)))
                .thenThrow(new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND));

        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/boards/9", authenticated()), CHANNEL))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an unauthenticated frame is refused rather than treated as anybody")
    void refusesAnAnonymousSubscription() {
        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/boards/7", null), CHANNEL))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(boardService);
    }

    @Test
    @DisplayName("a board destination that names no number is refused rather than ignored")
    void refusesAMalformedBoardDestination() {
        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/boards/7; drop", authenticated()), CHANNEL))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(boardService);
    }

    @Test
    @DisplayName("chat's own destinations travel this channel and are none of this class's business")
    void leavesEveryOtherDestinationAlone() {
        for (String destination : new String[] {"/topic/public", "/topic/room.4", "/user/a@b.c/queue/messages"}) {
            var message = subscribe(destination, authenticated());
            assertThat(interceptor.preSend(message, CHANNEL)).isSameAs(message);
        }
        verifyNoInteractions(boardService);
    }

    @Test
    @DisplayName("only SUBSCRIBE is checked; a CONNECT or a SEND passes through")
    void onlyChecksSubscribeFrames() {
        for (StompCommand command : new StompCommand[] {StompCommand.CONNECT, StompCommand.SEND, StompCommand.UNSUBSCRIBE}) {
            var accessor = StompHeaderAccessor.create(command);
            accessor.setDestination("/topic/boards/7");
            var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

            assertThat(interceptor.preSend(message, CHANNEL)).isSameAs(message);
        }
        verifyNoInteractions(boardService);
    }

    private static Message<byte[]> subscribe(String destination, Principal user) {
        var accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(user);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private UsernamePasswordAuthenticationToken authenticated() {
        return new UsernamePasswordAuthenticationToken(caller, null, caller.getAuthorities());
    }

    private static User caller() {
        var user = new User();
        user.setId(1);
        user.setEmail("member@kanban.local");
        return user;
    }
}
