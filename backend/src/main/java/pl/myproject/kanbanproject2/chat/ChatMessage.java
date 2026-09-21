package pl.myproject.kanbanproject2.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * What travels over STOMP, in both directions.
 *
 * <p>{@code boardId} replaced a free-text {@code roomId} in {@code V18}: a room the client named
 * was a room the server had no opinion about, so any string addressed a topic and an empty one
 * addressed the global topic everybody was on. A board id is checked - against
 * {@code BoardService.requireVisible}, the one place that question is answered.
 *
 * <p>{@code sender}, {@code type} and {@code timestamp} are stamped by the server on the way out
 * and whatever the client put in them is overwritten, which is why they are not validated.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChatMessage {

    private MessageType type;
    private String content;
    private String sender;
    private Integer boardId;
    private LocalDateTime timestamp;
    private String recipientId;
}
