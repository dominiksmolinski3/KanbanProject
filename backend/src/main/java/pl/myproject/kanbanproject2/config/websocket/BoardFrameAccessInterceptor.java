package pl.myproject.kanbanproject2.config.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import pl.myproject.kanbanproject2.board.BoardService;

@Component
@RequiredArgsConstructor
@Slf4j
public class BoardFrameAccessInterceptor implements ChannelInterceptor {

    private final StompSessionUsers sessionUsers;
    private final BoardService boardService;

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        var headers = message.getHeaders();
        if (SimpMessageHeaderAccessor.getMessageType(headers) != SimpMessageType.MESSAGE) {
            return message;
        }
        Integer boardId = BoardSubscriptionInterceptor.boardIdIn(SimpMessageHeaderAccessor.getDestination(headers));
        if (boardId == null) {
            return message;
        }

        String sessionId = SimpMessageHeaderAccessor.getSessionId(headers);
        Integer userId = sessionUsers.userOf(sessionId);
        boolean visible = sessionUsers.stillSees(sessionId, boardId,
                () -> boardService.isVisibleTo(userId, boardId));
        if (!visible) {
            log.debug("Withheld a frame on board {} from session {}, whose user no longer sees it", boardId, sessionId);
            return null;
        }
        return message;
    }
}
