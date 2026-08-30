package pl.myproject.kanbanproject2.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import pl.myproject.kanbanproject2.chat.ChatMessage;
import pl.myproject.kanbanproject2.chat.ChatService;
import pl.myproject.kanbanproject2.chat.MessageType;

import java.security.Principal;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatControllerTest {

    private ChatService chatService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        controller = new ChatController(chatService);
    }

    private static Principal principal(String name) {
        return () -> name;
    }

    private static ChatMessage message(String content) {
        return ChatMessage.builder().content(content).build();
    }

    private ChatMessage capturedTo(String destination) {
        var captor = forClass(ChatMessage.class);
        verify(chatService).sendMessage(eq(destination), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("public and room messages")
    class PublicAndRoom {

        @Test
        @DisplayName("a message with no room goes to the public topic")
        void noRoomGoesPublic() {
            controller.sendMessage(message("hello"), principal("anna"));

            var sent = capturedTo("/topic/public");
            assertThat(sent.getContent()).isEqualTo("hello");
            assertThat(sent.getType()).isEqualTo(MessageType.CHAT);
        }

        @Test
        @DisplayName("a message naming a room goes to that room's topic")
        void roomGoesToItsTopic() {
            var chatMessage = message("hello");
            chatMessage.setRoomId("design");

            controller.sendMessage(chatMessage, principal("anna"));

            assertThat(capturedTo("/topic/room.design").getRoomId()).isEqualTo("design");
        }

        @Test
        @DisplayName("an empty room id is treated as no room rather than as a room named empty")
        void emptyRoomIsNoRoom() {
            var chatMessage = message("hello");
            chatMessage.setRoomId("");

            controller.sendMessage(chatMessage, principal("anna"));

            capturedTo("/topic/public");
        }

        @Test
        @DisplayName("the sender is overwritten from the principal, never read off the payload")
        void senderComesFromThePrincipal() {
            var spoofed = message("hello");
            spoofed.setSender("someone-else");

            controller.sendMessage(spoofed, principal("anna"));

            assertThat(capturedTo("/topic/public").getSender()).isEqualTo("anna");
        }

        @Test
        @DisplayName("content is trimmed before it is broadcast")
        void contentIsTrimmed() {
            controller.sendMessage(message("  padded  "), principal("anna"));

            assertThat(capturedTo("/topic/public").getContent()).isEqualTo("padded");
        }

        @Test
        @DisplayName("every message is stamped with a timestamp on the server")
        void timestampIsServerSide() {
            controller.sendMessage(message("hello"), principal("anna"));

            assertThat(capturedTo("/topic/public").getTimestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an unauthenticated caller is refused before anything is sent")
        void unauthenticatedIsRefused() {
            assertThatThrownBy(() -> controller.sendMessage(message("hello"), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not authenticated");

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("a principal with a blank name is refused too")
        void blankPrincipalIsRefused() {
            assertThatThrownBy(() -> controller.sendMessage(message("hello"), principal("  ")))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("blank content is refused")
        void blankContentIsRefused() {
            assertThatThrownBy(() -> controller.sendMessage(message("   "), principal("anna")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("content is required");

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("null content is refused rather than dereferenced")
        void nullContentIsRefused() {
            assertThatThrownBy(() -> controller.sendMessage(message(null), principal("anna")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("content over the 2000-character cap is refused, so the broker is not the limit")
        void oversizeContentIsRefused() {
            var oversize = message("x".repeat(2001));

            assertThatThrownBy(() -> controller.sendMessage(oversize, principal("anna")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("too long");

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("content exactly at the cap is accepted - the boundary is inclusive")
        void contentAtTheCapIsAccepted() {
            controller.sendMessage(message("x".repeat(2000)), principal("anna"));

            assertThat(capturedTo("/topic/public").getContent()).hasSize(2000);
        }
    }

    @Nested
    @DisplayName("private messages")
    class Private {

        @Test
        @DisplayName("a private message is addressed to the recipient and echoed to the sender")
        void privateGoesToBothParties() {
            var chatMessage = message("psst");
            chatMessage.setRecipientId("bob");

            controller.sendPrivateMessage(chatMessage, principal("anna"));

            var captor = forClass(ChatMessage.class);
            verify(chatService).sendPrivateMessage(eq("bob"), eq("anna"), captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(MessageType.PRIVATE);
            assertThat(captor.getValue().getSender()).isEqualTo("anna");
        }

        @Test
        @DisplayName("a private message with no recipient is refused")
        void privateNeedsARecipient() {
            assertThatThrownBy(() -> controller.sendPrivateMessage(message("psst"), principal("anna")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recipient");

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("a blank recipient is refused as well")
        void blankRecipientIsRefused() {
            var chatMessage = message("psst");
            chatMessage.setRecipientId("   ");

            assertThatThrownBy(() -> controller.sendPrivateMessage(chatMessage, principal("anna")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("joining and leaving")
    class JoinAndLeave {

        private SimpMessageHeaderAccessor accessor() {
            var accessor = SimpMessageHeaderAccessor.create();
            accessor.setSessionAttributes(new HashMap<>());
            return accessor;
        }

        @Test
        @DisplayName("joining the public chat records the username on the session")
        void joinPublicRecordsTheUsername() {
            var accessor = accessor();

            controller.addUser(message(null), principal("anna"), accessor);

            assertThat(accessor.getSessionAttributes()).containsEntry("username", "anna");
            assertThat(accessor.getSessionAttributes()).doesNotContainKey("roomId");
            assertThat(capturedTo("/topic/public").getType()).isEqualTo(MessageType.JOIN);
        }

        @Test
        @DisplayName("joining a room records the room too, and announces in that room")
        void joinRoomRecordsTheRoom() {
            var accessor = accessor();
            var chatMessage = message(null);
            chatMessage.setRoomId("design");

            controller.addUser(chatMessage, principal("anna"), accessor);

            assertThat(accessor.getSessionAttributes()).containsEntry("roomId", "design");
            assertThat(capturedTo("/topic/room.design").getSender()).isEqualTo("anna");
        }

        @Test
        @DisplayName("joining does not require content - it is not a chat message")
        void joinNeedsNoContent() {
            controller.addUser(message(null), principal("anna"), accessor());

            verify(chatService).sendMessage(eq("/topic/public"), any(ChatMessage.class));
        }

        @Test
        @DisplayName("leaving announces a LEAVE in the room the caller names")
        void leaveAnnouncesInTheRoom() {
            controller.leaveRoom("design", principal("anna"));

            var sent = capturedTo("/topic/room.design");
            assertThat(sent.getType()).isEqualTo(MessageType.LEAVE);
            assertThat(sent.getSender()).isEqualTo("anna");
            assertThat(sent.getRoomId()).isEqualTo("design");
        }

        @Test
        @DisplayName("an unauthenticated caller cannot join or leave")
        void unauthenticatedCannotJoinOrLeave() {
            assertThatThrownBy(() -> controller.addUser(message(null), null, accessor()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> controller.leaveRoom("design", null))
                    .isInstanceOf(IllegalArgumentException.class);

            verifyNoInteractions(chatService);
        }
    }
}
