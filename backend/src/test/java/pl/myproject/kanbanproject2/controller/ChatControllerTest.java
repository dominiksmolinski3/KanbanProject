package pl.myproject.kanbanproject2.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.chat.ChatMessage;
import pl.myproject.kanbanproject2.chat.ChatRefusal;
import pl.myproject.kanbanproject2.chat.ChatService;
import pl.myproject.kanbanproject2.chat.MessageType;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What this suite is really about: <b>nothing in here throws, and nothing in here sends anywhere
 * the caller has not been let onto.</b> Both were true of neither the controller this replaced.
 */
class ChatControllerTest {

    private static final int BOARD_ID = 7;

    private ChatService chatService;
    private BoardService boardService;
    private ChatController controller;
    private Board board;
    private User anna;
    private User bob;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        boardService = mock(BoardService.class);
        controller = new ChatController(chatService, boardService);

        anna = user(1, "anna");
        bob = user(2, "bob");
        board = new Board();
        board.setId(BOARD_ID);
        when(boardService.requireVisible(any(User.class), eq(BOARD_ID))).thenReturn(board);
        // Write-allowed by default; the read-only refusal is its own test below.
        when(boardService.isWritable(any(User.class), any(Board.class))).thenReturn(true);
    }

    private static User user(int id, String username) {
        var user = new User();
        user.setId(id);
        user.setEmail(username);
        return user;
    }

    private static Principal principal(User user) {
        return new UsernamePasswordAuthenticationToken(user, null, List.of());
    }

    private static ChatMessage message(String content) {
        return ChatMessage.builder().content(content).boardId(BOARD_ID).build();
    }

    private static SimpMessageHeaderAccessor headers() {
        var accessor = SimpMessageHeaderAccessor.create();
        accessor.setSessionAttributes(new HashMap<>());
        return accessor;
    }

    private ChatMessage sentToBoard() {
        var captor = forClass(ChatMessage.class);
        verify(chatService).sendBoardMessage(eq(board), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("board messages")
    class BoardMessages {

        @Test
        @DisplayName("a message on a visible board is sent to that board")
        void visibleBoardIsSent() {
            controller.sendMessage(message("hello"), principal(anna));

            var sent = sentToBoard();
            assertThat(sent.getContent()).isEqualTo("hello");
            assertThat(sent.getType()).isEqualTo(MessageType.CHAT);
            assertThat(sent.getSender()).isEqualTo("anna");
            assertThat(sent.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("the sender is the authenticated account, whatever the payload claimed")
        void senderIsStampedOverThePayload() {
            var chatMessage = message("hello");
            chatMessage.setSender("someone-else");

            controller.sendMessage(chatMessage, principal(anna));

            assertThat(sentToBoard().getSender()).isEqualTo("anna");
        }

        @Test
        @DisplayName("content is trimmed before it is sent")
        void contentIsTrimmed() {
            controller.sendMessage(message("  hello  "), principal(anna));

            assertThat(sentToBoard().getContent()).isEqualTo("hello");
        }

        /*
         * The finding, as an assertion. A board the caller is not a member of used to be a room
         * name like any other, and the message went out on it.
         */
        @Test
        @DisplayName("a message to a board the caller cannot see is dropped, in silence")
        void invisibleBoardIsDropped() {
            when(boardService.requireVisible(any(User.class), eq(99)))
                    .thenThrow(new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND));
            var chatMessage = message("hello");
            chatMessage.setBoardId(99);

            assertThatCode(() -> controller.sendMessage(chatMessage, principal(anna)))
                    .doesNotThrowAnyException();

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("a message naming no board goes nowhere - there is no global room to fall back to")
        void noBoardHasNoFallback() {
            var chatMessage = message("hello");
            chatMessage.setBoardId(null);

            controller.sendMessage(chatMessage, principal(anna));

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("an unauthenticated frame is dropped rather than answered")
        void noPrincipalIsDropped() {
            assertThatCode(() -> controller.sendMessage(message("hello"), null))
                    .doesNotThrowAnyException();

            verifyNoInteractions(chatService);
            verifyNoInteractions(boardService);
        }

        /*
         * FEAT-08: read-only means read-only consistently, so a viewer's own board - one they can
         * see, unlike the invisibleBoardIsDropped case above - refuses the send rather than
         * dropping it in silence. The sender already knows their own role.
         */
        @Test
        @DisplayName("a viewer cannot post to the board's conversation, and is told so")
        void viewerIsRefusedNotDropped() {
            when(boardService.isWritable(anna, board)).thenReturn(false);

            controller.sendMessage(message("hello"), principal(anna));

            verify(chatService).refuse("anna", ChatRefusal.READ_ONLY_BOARD);
            verify(chatService, never()).sendBoardMessage(any(), any());
        }
    }

    @Nested
    @DisplayName("refusing without closing the session")
    class QuietRefusals {

        @Test
        @DisplayName("a message over the length limit is refused to the sender, not thrown")
        void tooLongIsRefusedQuietly() {
            var chatMessage = message("x".repeat(2001));

            assertThatCode(() -> controller.sendMessage(chatMessage, principal(anna)))
                    .as("a throw here becomes a STOMP ERROR frame and a closed session")
                    .doesNotThrowAnyException();

            verify(chatService).refuse("anna", ChatRefusal.MESSAGE_TOO_LONG);
            verify(chatService, never()).sendBoardMessage(any(), any());
        }

        @Test
        @DisplayName("a message at exactly the limit is sent")
        void exactlyTheLimitIsSent() {
            controller.sendMessage(message("x".repeat(2000)), principal(anna));

            assertThat(sentToBoard().getContent()).hasSize(2000);
        }

        @Test
        @DisplayName("a blank message is refused to the sender, not thrown")
        void blankIsRefusedQuietly() {
            controller.sendMessage(message("   "), principal(anna));

            verify(chatService).refuse("anna", ChatRefusal.EMPTY_MESSAGE);
            verify(chatService, never()).sendBoardMessage(any(), any());
        }

        @Test
        @DisplayName("a null content is refused to the sender, not thrown")
        void nullContentIsRefusedQuietly() {
            controller.sendMessage(message(null), principal(anna));

            verify(chatService).refuse("anna", ChatRefusal.EMPTY_MESSAGE);
        }

        @Test
        @DisplayName("a null payload is dropped without a refusal - there is no sender to answer")
        void nullPayloadIsDropped() {
            assertThatCode(() -> controller.sendMessage(null, principal(anna)))
                    .doesNotThrowAnyException();

            verifyNoInteractions(chatService);
        }
    }

    @Nested
    @DisplayName("direct messages")
    class DirectMessages {

        @Test
        @DisplayName("a direct message to a peer reaches both of them")
        void peerReceivesIt() {
            when(boardService.peersOf(anna)).thenReturn(List.of(anna, bob));
            var chatMessage = message("psst");
            chatMessage.setRecipientId("bob");

            controller.sendPrivateMessage(chatMessage, principal(anna));

            var captor = forClass(ChatMessage.class);
            verify(chatService).sendPrivateMessage(eq("bob"), eq("anna"), captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(MessageType.PRIVATE);
            assertThat(captor.getValue().getSender()).isEqualTo("anna");
            assertThat(captor.getValue().getBoardId())
                    .as("a direct message is not on a board, whatever the payload said")
                    .isNull();
        }

        /*
         * The other half of CHAT-01: the route addressed any recipientId it was handed, which walked
         * straight past the peer scoping GET /api/users exists to enforce.
         */
        @Test
        @DisplayName("a direct message to somebody sharing no board is dropped")
        void strangerIsDropped() {
            when(boardService.peersOf(anna)).thenReturn(List.of(anna));
            var chatMessage = message("psst");
            chatMessage.setRecipientId("bob");

            assertThatCode(() -> controller.sendPrivateMessage(chatMessage, principal(anna)))
                    .doesNotThrowAnyException();

            verify(chatService, never()).sendPrivateMessage(any(), any(), any());
        }

        @Test
        @DisplayName("the peer check ignores the case the address was typed in")
        void peerMatchIsCaseInsensitive() {
            when(boardService.peersOf(anna)).thenReturn(List.of(bob));
            var chatMessage = message("psst");
            chatMessage.setRecipientId("BOB");

            controller.sendPrivateMessage(chatMessage, principal(anna));

            verify(chatService).sendPrivateMessage(eq("BOB"), eq("anna"), any());
        }

        @Test
        @DisplayName("a direct message with no recipient is dropped rather than thrown")
        void missingRecipientIsDropped() {
            assertThatCode(() -> controller.sendPrivateMessage(message("psst"), principal(anna)))
                    .doesNotThrowAnyException();

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("a blank recipient is dropped as well")
        void blankRecipientIsDropped() {
            var chatMessage = message("psst");
            chatMessage.setRecipientId("   ");

            controller.sendPrivateMessage(chatMessage, principal(anna));

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("an over-long direct message is refused to its sender, not thrown")
        void tooLongDirectIsRefused() {
            when(boardService.peersOf(anna)).thenReturn(List.of(bob));
            var chatMessage = message("x".repeat(2001));
            chatMessage.setRecipientId("bob");

            controller.sendPrivateMessage(chatMessage, principal(anna));

            verify(chatService).refuse("anna", ChatRefusal.MESSAGE_TOO_LONG);
            verify(chatService, never()).sendPrivateMessage(any(), any(), any());
        }

        @Test
        @DisplayName("an unauthenticated direct message is dropped")
        void noPrincipalIsDropped() {
            assertThatCode(() -> controller.sendPrivateMessage(message("psst"), null))
                    .doesNotThrowAnyException();

            verifyNoInteractions(chatService);
        }
    }

    @Nested
    @DisplayName("presence")
    class Presence {

        @Test
        @DisplayName("joining a visible board announces on it and remembers which board it was")
        void joinAnnouncesAndRemembers() {
            var accessor = headers();

            controller.join(message("ignored"), principal(anna), accessor);

            var captor = forClass(ChatMessage.class);
            verify(chatService).announcePresence(eq(BOARD_ID), captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(MessageType.JOIN);
            assertThat(captor.getValue().getSender()).isEqualTo("anna");
            assertThat(accessor.getSessionAttributes())
                    .containsEntry(ChatController.BOARD_SESSION_ATTRIBUTE, BOARD_ID);
        }

        @Test
        @DisplayName("joining a board the caller cannot see announces nothing and remembers nothing")
        void joinOnInvisibleBoardIsDropped() {
            when(boardService.requireVisible(any(User.class), eq(99)))
                    .thenThrow(new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND));
            var chatMessage = message("ignored");
            chatMessage.setBoardId(99);
            var accessor = headers();

            controller.join(chatMessage, principal(anna), accessor);

            verifyNoInteractions(chatService);
            assertThat(accessor.getSessionAttributes())
                    .doesNotContainKey(ChatController.BOARD_SESSION_ATTRIBUTE);
        }

        @Test
        @DisplayName("leaving announces a LEAVE and forgets the board")
        void leaveAnnouncesAndForgets() {
            var accessor = headers();
            accessor.getSessionAttributes().put(ChatController.BOARD_SESSION_ATTRIBUTE, BOARD_ID);

            controller.leave(message("ignored"), principal(anna), accessor);

            var captor = forClass(ChatMessage.class);
            verify(chatService).announcePresence(eq(BOARD_ID), captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(MessageType.LEAVE);
            assertThat(accessor.getSessionAttributes())
                    .doesNotContainKey(ChatController.BOARD_SESSION_ATTRIBUTE);
        }

        @Test
        @DisplayName("presence frames need a principal too")
        void presenceNeedsAPrincipal() {
            var accessor = headers();

            controller.join(message("ignored"), null, accessor);
            controller.leave(message("ignored"), null, accessor);

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("presence naming no board is dropped")
        void presenceNeedsABoard() {
            var chatMessage = message("ignored");
            chatMessage.setBoardId(null);

            controller.join(chatMessage, principal(anna), headers());
            controller.leave(chatMessage, principal(anna), headers());

            verifyNoInteractions(chatService);
        }

        @Test
        @DisplayName("a session with no attribute map is survivable rather than an NPE")
        void missingSessionAttributesAreSurvivable() {
            var accessor = SimpMessageHeaderAccessor.create();

            assertThatCode(() -> controller.join(message("ignored"), principal(anna), accessor))
                    .doesNotThrowAnyException();

            verify(chatService).announcePresence(eq(BOARD_ID), any());
        }
    }

    @Test
    @DisplayName("a board lookup that fails for any reason still cannot close the session")
    void anyBoardFailureIsQuiet() {
        doThrow(new GlobalException(ExceptionIdentifier.BOARD_NOT_FOUND))
                .when(boardService).requireVisible(any(User.class), anyInt());

        assertThatCode(() -> controller.sendMessage(message("hello"), principal(anna)))
                .doesNotThrowAnyException();
    }
}
