package pl.myproject.kanbanproject2.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatServiceTest {

    private ChatRepository chatRepository;
    private ChatService service;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        chatRepository = mock(ChatRepository.class);
        service = new ChatService(messagingTemplate, chatRepository);
    }

    private static ChatMessage message() {
        return ChatMessage.builder()
                .type(MessageType.CHAT)
                .content("hello")
                .sender("anna")
                .roomId("design")
                .timestamp(LocalDateTime.of(2026, 8, 29, 12, 0))
                .build();
    }

    private Chat persisted() {
        var captor = forClass(Chat.class);
        verify(chatRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a broadcast goes to the destination it is given")
    void broadcastGoesToTheDestination() {
        var chatMessage = message();

        service.sendMessage("/topic/room.design", chatMessage);

        verify(messagingTemplate).convertAndSend("/topic/room.design", chatMessage);
    }

    @Test
    @DisplayName("a broadcast is persisted field for field")
    void broadcastIsPersisted() {
        service.sendMessage("/topic/public", message());

        var saved = persisted();
        assertThat(saved.getType()).isEqualTo(MessageType.CHAT);
        assertThat(saved.getContent()).isEqualTo("hello");
        assertThat(saved.getSender()).isEqualTo("anna");
        assertThat(saved.getRoomId()).isEqualTo("design");
        assertThat(saved.getTimestamp()).isEqualTo(LocalDateTime.of(2026, 8, 29, 12, 0));
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
    @DisplayName("a private message is stored once, with the recipient on it")
    void privateIsStoredOnce() {
        var chatMessage = message();
        chatMessage.setType(MessageType.PRIVATE);
        chatMessage.setRecipientId("bob");

        service.sendPrivateMessage("bob", "anna", chatMessage);

        var saved = persisted();
        assertThat(saved.getRecipientId()).isEqualTo("bob");
        assertThat(saved.getType()).isEqualTo(MessageType.PRIVATE);
    }

    @Test
    @DisplayName("the factory stamps a CHAT message with a timestamp of its own")
    void factoryStampsTheMessage() {
        var chatMessage = ChatMessage.createMessage("hi", "anna", "design");

        assertThat(chatMessage.getType()).isEqualTo(MessageType.CHAT);
        assertThat(chatMessage.getContent()).isEqualTo("hi");
        assertThat(chatMessage.getSender()).isEqualTo("anna");
        assertThat(chatMessage.getRoomId()).isEqualTo("design");
        assertThat(chatMessage.getTimestamp()).isNotNull();
    }
}
