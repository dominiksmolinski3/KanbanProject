package pl.myproject.kanbanproject2.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.BoardService;
import pl.myproject.kanbanproject2.chat.ChatMessage;
import pl.myproject.kanbanproject2.chat.ChatRefusal;
import pl.myproject.kanbanproject2.chat.ChatService;
import pl.myproject.kanbanproject2.chat.MessageType;
import pl.myproject.kanbanproject2.config.websocket.StompPrincipals;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.security.Principal;
import java.time.LocalDateTime;

/**
 * Chat, scoped to a board like everything else here.
 *
 * <p>It was not, and that was the one place the boards-with-members model never reached: the
 * destination was built out of a {@code roomId} the client supplied, so any string named a topic
 * and an empty one named {@code /topic/public} - a single global room every signed-in account
 * subscribed to on connect, where a member of one board read the messages of every other board's
 * members. A direct message had the matching hole from the other side, addressing any
 * {@code recipientId} at all and walking straight past the peer scoping {@code GET /api/users}
 * exists to enforce.
 *
 * <p>Two rules hold the rewrite, and both are this repository's own, kept one channel over:
 *
 * <ul>
 *   <li><b>Nothing here throws.</b> An exception on the inbound channel becomes a STOMP ERROR
 *       frame and a <em>closed session</em>, so a paste over the length limit did not bounce the
 *       message - it dropped the connection, and the client reconnected into a room it had to
 *       rejoin. {@code BoardSubscriptionInterceptor} already wrote this reasoning down for
 *       SUBSCRIBE; a refusal here is a dropped frame plus a WARN, and nothing else.</li>
 *   <li><b>A refusal the sender already knows about is answered; one that turns on who they are is
 *       not.</b> Blank and over-long come back on the sender's own error queue, because they are
 *       looking at the message that caused them. A board they may not post to is silence, or the
 *       refusal would confirm the board is real - the 404-not-403 rule in the form STOMP allows.
 *       </li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private static final int MAX_MESSAGE_LENGTH = 2000;

    /** Which board this session announced itself on, for the LEAVE the disconnect listener sends. */
    public static final String BOARD_SESSION_ATTRIBUTE = "boardId";

    private final ChatService chatService;
    private final BoardService boardService;

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessage chatMessage, Principal principal) {
        User sender = callerOr(principal, "send a message");
        if (sender == null) {
            return;
        }
        Board board = visibleBoardOr(sender, chatMessage, "send a message");
        if (board == null) {
            return;
        }
        // Read-only means read-only consistently: a viewer can watch the board's conversation
        // (BoardSubscriptionInterceptor asks only requireVisible) but not add to it. Answered rather
        // than dropped, unlike a board the caller cannot see at all - the sender already knows their
        // own role, so silence here would read as a delivery failure rather than a refusal.
        if (!boardService.isWritable(sender, board)) {
            log.warn("Dropped an attempt by {} to send a message on read-only board {}",
                    sender.getUsername(), board.getId());
            chatService.refuse(sender.getUsername(), ChatRefusal.READ_ONLY_BOARD);
            return;
        }
        if (!prepare(chatMessage, sender, MessageType.CHAT)) {
            return;
        }

        chatService.sendBoardMessage(board, chatMessage);
    }

    /**
     * A direct message reaches a peer - somebody the sender shares at least one board with - and
     * nobody else. The check is {@code BoardService.peersOf}, the same collection
     * {@code GET /api/users} is built from, so the two cannot disagree about who is reachable.
     */
    @MessageMapping("/chat.sendPrivateMessage")
    public void sendPrivateMessage(@Payload ChatMessage chatMessage, Principal principal) {
        User sender = callerOr(principal, "send a direct message");
        if (sender == null) {
            return;
        }
        String recipient = chatMessage == null ? null : chatMessage.getRecipientId();
        if (recipient == null || recipient.isBlank() || !isPeer(sender, recipient)) {
            log.warn("Dropped a direct message from {} to an account that is not a peer", sender.getUsername());
            return;
        }
        if (!prepare(chatMessage, sender, MessageType.PRIVATE)) {
            return;
        }
        chatMessage.setBoardId(null);

        chatService.sendPrivateMessage(recipient, sender.getUsername(), chatMessage);
    }

    /**
     * Presence on a board. Sent, never stored - see {@code ChatService.announcePresence}. The board
     * goes on the session so the disconnect listener knows where to say the person went.
     */
    @MessageMapping("/chat.join")
    public void join(@Payload ChatMessage chatMessage,
                     Principal principal,
                     SimpMessageHeaderAccessor headerAccessor) {
        User sender = callerOr(principal, "join a board");
        if (sender == null) {
            return;
        }
        Board board = visibleBoardOr(sender, chatMessage, "join a board");
        if (board == null) {
            return;
        }

        if (headerAccessor.getSessionAttributes() != null) {
            headerAccessor.getSessionAttributes().put(BOARD_SESSION_ATTRIBUTE, board.getId());
        }
        chatService.announcePresence(board.getId(), presence(sender, board.getId(), MessageType.JOIN));
    }

    @MessageMapping("/chat.leave")
    public void leave(@Payload ChatMessage chatMessage,
                      Principal principal,
                      SimpMessageHeaderAccessor headerAccessor) {
        User sender = callerOr(principal, "leave a board");
        if (sender == null) {
            return;
        }
        Board board = visibleBoardOr(sender, chatMessage, "leave a board");
        if (board == null) {
            return;
        }

        if (headerAccessor.getSessionAttributes() != null) {
            headerAccessor.getSessionAttributes().remove(BOARD_SESSION_ATTRIBUTE);
        }
        chatService.announcePresence(board.getId(), presence(sender, board.getId(), MessageType.LEAVE));
    }

    private static ChatMessage presence(User sender, Integer boardId, MessageType type) {
        return ChatMessage.builder()
                .type(type)
                .sender(sender.getUsername())
                .boardId(boardId)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Stamps the server's own view of sender, type and time over whatever the client sent, and
     * answers the sender when their own content is why nothing was sent. False means "do not send".
     */
    private boolean prepare(ChatMessage chatMessage, User sender, MessageType type) {
        String content = chatMessage.getContent();
        if (content == null || content.isBlank()) {
            chatService.refuse(sender.getUsername(), ChatRefusal.EMPTY_MESSAGE);
            return false;
        }
        String normalized = content.trim();
        if (normalized.length() > MAX_MESSAGE_LENGTH) {
            chatService.refuse(sender.getUsername(), ChatRefusal.MESSAGE_TOO_LONG);
            return false;
        }

        chatMessage.setSender(sender.getUsername());
        chatMessage.setType(type);
        chatMessage.setContent(normalized);
        chatMessage.setTimestamp(LocalDateTime.now());
        return true;
    }

    private User callerOr(Principal principal, String attempted) {
        User caller = StompPrincipals.userOf(principal);
        if (caller == null) {
            log.warn("Dropped an attempt to {} with no principal on the session", attempted);
        }
        return caller;
    }

    /** Null when the frame names no board, or none this caller may see. Silence either way. */
    private Board visibleBoardOr(User caller, ChatMessage chatMessage, String attempted) {
        Integer boardId = chatMessage == null ? null : chatMessage.getBoardId();
        if (boardId == null) {
            log.warn("Dropped an attempt by {} to {} naming no board", caller.getUsername(), attempted);
            return null;
        }
        try {
            return boardService.requireVisible(caller, boardId);
        } catch (GlobalException exception) {
            log.warn("Dropped an attempt by {} to {} on board {}", caller.getUsername(), attempted, boardId);
            return null;
        }
    }

    private boolean isPeer(User sender, String recipient) {
        return boardService.peersOf(sender).stream()
                .anyMatch(peer -> recipient.equalsIgnoreCase(peer.getUsername()));
    }
}
