package pl.myproject.kanbanproject2.config.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.security.Principal;

/**
 * Decides who may listen to a board, which is the only thing standing between
 * {@code /topic/boards/{id}} and every signed-in account.
 *
 * <p>{@link WebSocketAuthInterceptor} answers <em>whether</em> a caller is anybody; it does not
 * answer <em>which</em> destinations that caller may have. The simple broker does not either: a
 * topic has no notion of who is allowed on it, so without this a subscriber holding any valid
 * token could sit on any board's destination. The frames carry only a kind and an id - see
 * {@link pl.myproject.kanbanproject2.board.event.BoardEvent} - so what leaked would be "board 7
 * exists and somebody just changed it" rather than anybody's data, and that is still an oracle of
 * exactly the kind every route here answers 404 to avoid.
 *
 * <p><b>A refused subscription is the same 404 the REST route gives</b>, raised through
 * {@link BoardService} so that "may this caller see this board" is answered in one place and not
 * re-implemented against the member list here. A board that does not exist and a board that
 * belongs to somebody else are the same answer for the same reason they are over HTTP: a
 * distinguishable refusal maps other people's boards by walking ids.
 *
 * <p>This runs after the authentication interceptor, which is what puts the principal on the
 * session; ordering is declared in {@link WebSocketConfig} and asserted in its test, because the
 * other order would read every subscription as anonymous.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BoardSubscriptionInterceptor implements ChannelInterceptor {

    private final BoardService boardService;

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        Integer boardId = boardIdIn(accessor.getDestination());
        if (boardId == null) {
            return message;
        }

        User caller = callerOf(accessor.getUser());
        if (caller == null) {
            throw new AccessDeniedException("A board subscription requires an authenticated session");
        }

        try {
            boardService.requireVisible(caller, boardId);
        } catch (GlobalException exception) {
            log.warn("Refused a board subscription for {} to board {}", caller.getUsername(), boardId);
            throw new AccessDeniedException("That board is not available to this account");
        }

        return message;
    }

    /**
     * The board id in a board destination, or null for every other destination.
     *
     * <p>Chat's own topics travel this channel too, so anything that is not a board destination has
     * to pass through untouched rather than be refused. A destination under the prefix whose last
     * segment is not a number is nothing this application publishes to, and is refused by being
     * treated as a board id that no board has.
     */
    private static Integer boardIdIn(String destination) {
        if (destination == null || !destination.startsWith(BoardEventPublisher.DESTINATION_PREFIX)) {
            return null;
        }
        String suffix = destination.substring(BoardEventPublisher.DESTINATION_PREFIX.length());
        try {
            return Integer.valueOf(suffix);
        } catch (NumberFormatException exception) {
            throw new AccessDeniedException("That is not a board destination");
        }
    }

    private static User callerOf(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof User user) {
            return user;
        }
        return null;
    }
}
