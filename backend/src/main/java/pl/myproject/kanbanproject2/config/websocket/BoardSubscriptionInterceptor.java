package pl.myproject.kanbanproject2.config.websocket;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.security.Principal;

/**
 * Decides who may listen to a board - the only thing standing between {@code /topic/boards.{id}},
 * the chat topic beneath it, and every signed-in account, since the broker has no notion of who is
 * allowed on a topic. Visibility is asked of {@link BoardService}, the same place the REST routes
 * answer it.
 *
 * <p><b>A refused subscription is dropped rather than answered</b>, this application's 404-not-403
 * rule in the one form STOMP allows. Throwing would turn into an ERROR frame that closes the
 * session, so a removed member would reconnect, resubscribe and be closed again every five
 * seconds - and the refusal itself would confirm the board is real. Returning {@code null} drops
 * the frame instead: the connection survives, and every drop is logged at WARN as the diagnosis
 * path for what would otherwise look like a feature quietly doing nothing.
 *
 * <p>This runs after the authentication interceptor, which puts the principal on the session;
 * ordering is declared in {@link WebSocketConfig} and asserted in its test.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BoardSubscriptionInterceptor implements ChannelInterceptor {

    /** A board id no board has, for a destination under the prefix that names no number. */
    private static final Integer NOT_A_BOARD = -1;

    /**
     * Counts a dropped subscription, tagged by why - {@code no-principal} for a session the
     * authentication interceptor never stamped, {@code not-visible} for a board the caller cannot
     * see (which is also what a malformed destination's {@link #NOT_A_BOARD} id resolves to). The
     * broker gives this no other signal: a drop here is a silently discarded frame by design, and
     * this is the trend line the WARN log alone cannot give.
     */
    static final String DROPPED_COUNTER = "kanban.board.subscription.dropped";

    private final BoardService boardService;
    private final MeterRegistry meterRegistry;

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
            meterRegistry.counter(DROPPED_COUNTER, "reason", "no-principal").increment();
            return null;
        }

        try {
            boardService.requireVisible(caller, boardId);
        } catch (GlobalException exception) {
            log.warn("Dropped a board subscription for {} to board {}", caller.getUsername(), boardId);
            meterRegistry.counter(DROPPED_COUNTER, "reason", "not-visible").increment();
            return null;
        }

        return message;
    }

    /**
     * The board id in a board destination, or null for every other destination.
     *
     * <p><b>Only the first segment after the prefix is the id</b>, which is what lets one check
     * cover both {@code /topic/boards.7} (the change events) and {@code /topic/boards.7.chat} (the
     * board's conversation). Chat used to sit outside the prefix altogether, on a global
     * {@code /topic/public} and on rooms the client named, and this method's own Javadoc said so -
     * which is how a feature with no tenancy went unnoticed in a repository that checks everything
     * else. A destination under the prefix whose first segment is not a number is treated as a
     * board id nothing has, and dropped the same way.
     */
    private static Integer boardIdIn(String destination) {
        if (destination == null || !destination.startsWith(BoardEventPublisher.DESTINATION_PREFIX)) {
            return null;
        }
        String suffix = destination.substring(BoardEventPublisher.DESTINATION_PREFIX.length());
        int nextSegment = suffix.indexOf('.');
        String id = nextSegment < 0 ? suffix : suffix.substring(0, nextSegment);
        try {
            return Integer.valueOf(id);
        } catch (NumberFormatException exception) {
            return NOT_A_BOARD;
        }
    }

    private static User callerOf(Principal principal) {
        return StompPrincipals.userOf(principal);
    }
}
