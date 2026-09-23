package pl.myproject.kanbanproject2.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

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
