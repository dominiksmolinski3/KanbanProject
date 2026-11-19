package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

@RestController
@RequestMapping("/blocks")
@RequiredArgsConstructor
public class BlockController {

    private final ConversationService conversations;

    @GetMapping
    public ResponseEntity<List<BlockDto>> list(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(conversations.blocks(currentUser));
    }

    @PostMapping
    public ResponseEntity<Void> block(@Valid @RequestBody BlockRequest request,
                                      @AuthenticationPrincipal User currentUser) {
        conversations.block(currentUser, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> unblock(@PathVariable Integer userId, @AuthenticationPrincipal User currentUser) {
        conversations.unblock(currentUser, userId);
        return ResponseEntity.noContent().build();
    }
}
