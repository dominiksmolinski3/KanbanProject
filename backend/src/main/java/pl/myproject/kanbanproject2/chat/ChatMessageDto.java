package pl.myproject.kanbanproject2.chat;

import java.time.LocalDateTime;

public record ChatMessageDto(Integer id,
                             MessageType type,
                             String content,
                             String sender,
                             Integer boardId,
                             String recipientId,
                             LocalDateTime timestamp) {
}
