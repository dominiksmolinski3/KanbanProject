package pl.myproject.kanbanproject2.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    public static final String CHAT_SUFFIX = ".chat";

    public static final String ERROR_QUEUE = "/queue/errors";

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatRepository chatRepository;

    public static String boardDestination(Integer boardId) {
        return BoardEventPublisher.DESTINATION_PREFIX + boardId + CHAT_SUFFIX;
    }

    public void sendBoardMessage(Board board, ChatMessage message) {
        message.setBoardId(board.getId());
        messagingTemplate.convertAndSend(boardDestination(board.getId()), message);
        save(message, board);
    }

    public void sendPrivateMessage(String recipientUsername, String senderUsername, ChatMessage message) {
        messagingTemplate.convertAndSendToUser(recipientUsername, "/queue/messages", message);
        messagingTemplate.convertAndSendToUser(senderUsername, "/queue/messages", message);
        save(message, null);
    }

    public void announcePresence(Integer boardId, ChatMessage message) {
        messagingTemplate.convertAndSend(boardDestination(boardId), message);
    }

    public void refuse(String senderUsername, String reasonKey) {
        messagingTemplate.convertAndSendToUser(senderUsername, ERROR_QUEUE, new ChatRefusal(reasonKey));
    }

    private void save(ChatMessage message, Board board) {
        var entity = Chat.builder()
                .type(message.getType())
                .content(message.getContent())
                .sender(message.getSender())
                .board(board)
                .timestamp(message.getTimestamp())
                .recipientId(message.getRecipientId())
                .build();

        chatRepository.save(entity);
    }
}
