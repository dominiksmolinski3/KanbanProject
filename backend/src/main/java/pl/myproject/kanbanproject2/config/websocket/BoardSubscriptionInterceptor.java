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
 * <p><b>A refused subscription is dropped rather than answered</b>, which is this application's
 * 404-not-403 rule kept in the one form STOMP allows. Throwing is the obvious thing and is worse
 * twice over: Spring turns an exception on the inbound channel into an ERROR frame and
 * <em>closes the session</em>, so a member removed from a board while watching it would reconnect,
 * resubscribe, be refused and be closed again every five seconds forever - and the refusal itself
 * tells a caller that the board is real. Returning {@code null} discards the frame instead: the
 * connection survives with its other subscriptions intact, and a board the caller may not see is
 * indistinguishable from a board where nothing is happening.
 *
 * <p>The cost is that a misconfiguration here would look like a feature that quietly does nothing,
 * which is the failure mode this repository dislikes most - so every drop is logged at WARN, and
 * that log is the diagnosis path.
 *
 * <p>Whether the caller may see the board is asked of {@link BoardService}, so that question is
 * answered where the REST routes answer it rather than re-implemented against the member list.
 *
 * <p>This runs after the authentication interceptor, which is what puts the principal on the
 * session; ordering is declared in {@link WebSocketConfig} and asserted in its test, because the
 * other order would read every subscription as anonymous.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BoardSubscriptionInterceptor implements ChannelInterceptor {

    /** A board id no board has, for a destination under the prefix that names no number. */
    private static final Integer NOT_A_BOARD = -1;

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
            log.warn("Dropped a board subscription with no principal on the session");
            return null;
        }

        try {
            boardService.requireVisible(caller, boardId);
        } catch (GlobalException exception) {
            log.warn("Dropped a board subscription for {} to board {}", caller.getUsername(), boardId);
            return null;
        }

        return message;
    }

    /**
     * The board id in a board destination, or null for every other destination.
     *
     * <p>Chat's own topics travel this channel too, so anything that is not a board destination has
     * to pass through untouched rather than be refused. A destination under the prefix whose last
     * segment is not a number is nothing this application publishes to, and is refused by being
     * treated as a board id no board has, and dropped like any other board nobody may see.
     */
    private static Integer boardIdIn(String destination) {
        if (destination == null || !destination.startsWith(BoardEventPublisher.DESTINATION_PREFIX)) {
            return null;
        }
        String suffix = destination.substring(BoardEventPublisher.DESTINATION_PREFIX.length());
        try {
            return Integer.valueOf(suffix);
        } catch (NumberFormatException exception) {
            return NOT_A_BOARD;
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
