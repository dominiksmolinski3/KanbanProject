package pl.myproject.kanbanproject2.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

/**
 * Scroll-back. Two reads, because there are two kinds of message and one page holding both would
 * answer neither question: a board's conversation, and the thread with one peer.
 *
 * <p>{@code ?boardId=} is optional and means the caller's own board, the convention every other
 * listing here uses. Named {@code ChatHistoryController} rather than {@code ChatController},
 * which is the STOMP one in {@code controller/} and maps no HTTP route at all.
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatHistoryController {

    private final ChatHistoryService historyService;

    @GetMapping
    public ResponseEntity<ChatMessageResults> boardHistory(
            @RequestParam(required = false) Integer boardId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(historyService.boardHistory(currentUser, boardId, page, size));
    }

    @GetMapping("/direct")
    public ResponseEntity<ChatMessageResults> directHistory(
            @RequestParam(name = "with") String peer,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(historyService.directHistory(currentUser, peer, page, size));
    }
}
