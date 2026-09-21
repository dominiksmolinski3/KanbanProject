package pl.myproject.kanbanproject2.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import pl.myproject.kanbanproject2.board.Board;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatServiceTest {

    private static final LocalDateTime SENT_AT = LocalDateTime.of(2026, 8, 29, 12, 0);

    private SimpMessagingTemplate messagingTemplate;
    private ChatRepository chatRepository;
    private ChatService service;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        chatRepository = mock(ChatRepository.class);
        service = new ChatService(messagingTemplate, chatRepository);
    }

    private static Board board(int id) {
        var board = new Board();
        board.setId(id);
        return board;
    }

    private static ChatMessage message() {
        return ChatMessage.builder()
                .type(MessageType.CHAT)
                .content("hello")
                .sender("anna")
                .timestamp(SENT_AT)
                .build();
    }

    private Chat persisted() {
        var captor = forClass(Chat.class);
        verify(chatRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a board message goes to that board's chat topic, under the prefix the interceptor guards")
    void boardMessageGoesToTheBoardTopic() {
        var chatMessage = message();

        service.sendBoardMessage(board(7), chatMessage);

        verify(messagingTemplate).convertAndSend("/topic/boards.7.chat", chatMessage);
    }

    @Test
    @DisplayName("the chat suffix is separated with a dot, which RabbitMQ's routing key requires")
    void theDestinationUsesDotsOnly() {
        assertThat(ChatService.boardDestination(7)).isEqualTo("/topic/boards.7.chat");
        assertThat(ChatService.boardDestination(7).substring("/topic/".length()))
                .as("everything after /topic/ is one AMQP routing key and may not contain a slash")
                .doesNotContain("/");
    }

    @Test
    @DisplayName("the board is stamped onto the outgoing frame as well as the row")
    void theBoardIsStampedOnTheFrame() {
        var chatMessage = message();

        service.sendBoardMessage(board(7), chatMessage);

        assertThat(chatMessage.getBoardId()).isEqualTo(7);
        assertThat(persisted().getBoard().getId()).isEqualTo(7);
    }

    @Test
    @DisplayName("a board message is persisted field for field")
    void boardMessageIsPersisted() {
        service.sendBoardMessage(board(7), message());

        var saved = persisted();
        assertThat(saved.getType()).isEqualTo(MessageType.CHAT);
        assertThat(saved.getContent()).isEqualTo("hello");
        assertThat(saved.getSender()).isEqualTo("anna");
        assertThat(saved.getTimestamp()).isEqualTo(SENT_AT);
        assertThat(saved.getRecipientId()).isNull();
    }

    @Test
    @DisplayName("a private message reaches the recipient and the sender both")
    void privateReachesBothParties() {
        var chatMessage = message();
        chatMessage.setType(MessageType.PRIVATE);
        chatMessage.setRecipientId("bob");

        service.sendPrivateMessage("bob", "anna", chatMessage);

        verify(messagingTemplate).convertAndSendToUser(eq("bob"), eq("/queue/messages"), eq(chatMessage));
        verify(messagingTemplate).convertAndSendToUser(eq("anna"), eq("/queue/messages"), eq(chatMessage));
    }

    @Test
    @DisplayName("a private message is stored once, on no board")
    void privateIsStoredOnceWithNoBoard() {
        var chatMessage = message();
        chatMessage.setType(MessageType.PRIVATE);
        chatMessage.setRecipientId("bob");

        service.sendPrivateMessage("bob", "anna", chatMessage);

        var saved = persisted();
        assertThat(saved.getRecipientId()).isEqualTo("bob");
        assertThat(saved.getType()).isEqualTo(MessageType.PRIVATE);
        assertThat(saved.getBoard())
                .as("exactly one of board and recipient is set on any row this application writes")
                .isNull();
    }

    @Test
    @DisplayName("presence is announced and not stored")
    void presenceIsNotStored() {
        var joined = ChatMessage.builder().type(MessageType.JOIN).sender("anna").boardId(7).build();

        service.announcePresence(7, joined);

        verify(messagingTemplate).convertAndSend("/topic/boards.7.chat", joined);
        verifyNoInteractions(chatRepository);
    }

    @Test
    @DisplayName("a refusal reaches the sender alone, as a key rather than a sentence")
    void refusalGoesToTheSenderAsAKey() {
        service.refuse("anna", ChatRefusal.MESSAGE_TOO_LONG);

        var captor = forClass(ChatRefusal.class);
        verify(messagingTemplate).convertAndSendToUser(eq("anna"), eq("/queue/errors"), captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo("chat.errors.tooLong");
        assertThat(captor.getValue().reason())
                .as("a refusal is a translation key the client renders, never wording composed here")
                .doesNotContain(" ");
        verifyNoInteractions(chatRepository);
    }

    @Test
    @DisplayName("a refusal is never broadcast to a topic")
    void refusalIsNeverBroadcast() {
        service.refuse("anna", ChatRefusal.EMPTY_MESSAGE);

        verify(messagingTemplate, never()).convertAndSend(any(String.class), any(Object.class));
    }
}
