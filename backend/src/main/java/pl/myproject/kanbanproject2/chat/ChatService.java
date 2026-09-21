package pl.myproject.kanbanproject2.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.event.BoardEventPublisher;

/**
 * The write side of chat: what goes on the wire, and what is kept.
 *
 * <p>A board message travels {@link #boardDestination}, which is the board's own topic with a
 * {@code .chat} suffix - <b>a dot, not a slash</b>, for the reason
 * {@link BoardEventPublisher#DESTINATION_PREFIX} states at length: RabbitMQ's STOMP plugin reads
 * everything after {@code /topic/} as one AMQP routing key and refuses the whole destination the
 * moment it contains a further {@code /}. Sharing the prefix is not tidiness either - it is what
 * puts chat behind {@code BoardSubscriptionInterceptor}, which already asks
 * {@code BoardService.requireVisible} before letting anybody listen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatService {

    /**
     * Appended to a board's own destination rather than given a prefix of its own, so that one
     * interceptor authorises both and there is no second place to remember.
     */
    public static final String CHAT_SUFFIX = ".chat";

    /** Where a refusal the sender is allowed to know about is delivered. */
    public static final String ERROR_QUEUE = "/queue/errors";

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatRepository chatRepository;

    public static String boardDestination(Integer boardId) {
        return BoardEventPublisher.DESTINATION_PREFIX + boardId + CHAT_SUFFIX;
    }

    /** Sends to the board's subscribers and keeps the row the history routes read back. */
    public void sendBoardMessage(Board board, ChatMessage message) {
        message.setBoardId(board.getId());
        messagingTemplate.convertAndSend(boardDestination(board.getId()), message);
        save(message, board);
    }

    /**
     * Sends to both ends of the thread and keeps one row. The sender gets their own copy because
     * the client renders what it receives rather than echoing what it typed, so a message the
     * sender never sees is a message they cannot tell was delivered.
     */
    public void sendPrivateMessage(String recipientUsername, String senderUsername, ChatMessage message) {
        messagingTemplate.convertAndSendToUser(recipientUsername, "/queue/messages", message);
        messagingTemplate.convertAndSendToUser(senderUsername, "/queue/messages", message);
        save(message, null);
    }

    /**
     * Presence, which is sent and not stored: "X joined" is worth a line in the panel while
     * somebody is looking at it and is not worth a row in the scroll-back, where a reconnecting
     * client would bury the conversation under its own comings and goings.
     */
    public void announcePresence(Integer boardId, ChatMessage message) {
        messagingTemplate.convertAndSend(boardDestination(boardId), message);
    }

    /**
     * Tells one sender their own message was not sent. Only ever used for something the sender
     * already knows - their message was blank, or too long. <b>A refusal that turns on who the
     * caller is is dropped in silence instead</b>, the 404-not-403 rule as STOMP allows it to be
     * kept: answering "you may not post to board 7" confirms board 7 is real.
     *
     * <p>The payload is a key, never a sentence. The wording belongs to the nine client bundles,
     * the same rule {@code TaskActivityRecorder} and the keyboard-move announcement follow.
     */
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
