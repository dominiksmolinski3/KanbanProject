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

@Component
@RequiredArgsConstructor
@Slf4j
public class BoardSubscriptionInterceptor implements ChannelInterceptor {

    private static final Integer NOT_A_BOARD = -1;

    static final String DROPPED_COUNTER = "kanban.board.subscription.dropped";

    private final BoardService boardService;
    private final MeterRegistry meterRegistry;

    @Override
    // Refusals return null (drop the frame), never throw: an exception closes the session and confirms the board exists.
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
