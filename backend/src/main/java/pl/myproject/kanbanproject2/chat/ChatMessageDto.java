package pl.myproject.kanbanproject2.chat;

import java.time.LocalDateTime;

/**
 * One stored message as the history routes serve it. Shaped to match what the live STOMP frame
 * carries, so the client renders a message the same way whether it arrived over the socket or came
 * back from the scroll-back.
 */
public record ChatMessageDto(Integer id,
                             MessageType type,
                             String content,
                             String sender,
                             Integer boardId,
                             String recipientId,
                             LocalDateTime timestamp) {
}
