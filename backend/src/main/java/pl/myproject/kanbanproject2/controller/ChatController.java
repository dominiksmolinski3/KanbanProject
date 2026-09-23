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

// Handlers never throw: an exception on the inbound channel closes the client's STOMP session.
@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private static final int MAX_MESSAGE_LENGTH = 2000;

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
