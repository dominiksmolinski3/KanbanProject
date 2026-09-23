package pl.myproject.kanbanproject2.config.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import pl.myproject.kanbanproject2.chat.ChatMessage;
import pl.myproject.kanbanproject2.chat.ChatService;
import pl.myproject.kanbanproject2.chat.MessageType;
import pl.myproject.kanbanproject2.controller.ChatController;

import java.time.LocalDateTime;
import java.util.Map;

@Component
@Slf4j
@RequiredArgsConstructor
public class WebSocketEventListener {

    private final ChatService chatService;

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> attributes = headerAccessor.getSessionAttributes();
        if (attributes == null) {
            return;
        }

        String username = (String) attributes.get("username");
        Integer boardId = (Integer) attributes.get(ChatController.BOARD_SESSION_ATTRIBUTE);
        if (username == null || boardId == null) {
            return;
        }

        log.info("User {} disconnected from board {}", username, boardId);
        chatService.announcePresence(boardId, ChatMessage.builder()
                .type(MessageType.LEAVE)
                .sender(username)
                .boardId(boardId)
                .timestamp(LocalDateTime.now())
                .build());
    }
}
