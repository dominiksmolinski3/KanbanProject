package pl.myproject.kanbanproject2.board.invitation;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

/**
 * The owner's half of an invitation: send one, see what is outstanding, take one back.
 *
 * <p>Nested under the board because an invitation has no board of its own to be scoped by - it is
 * the board's, entirely, which is the same reason attachments are nested under their task. The
 * invitee's half lives at {@code /invitations} instead, because that listing does not start from a
 * board the caller can necessarily see.
 */
@RestController
@RequestMapping("/boards/{boardId}/invitations")
@RequiredArgsConstructor
public class BoardInvitationController {

    private final BoardInvitationService invitationService;

    @PostMapping
    public ResponseEntity<BoardInvitationDto> invite(@PathVariable Integer boardId,
                                                     @Valid @RequestBody InviteRequest request,
                                                     @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invitationService.invite(currentUser, boardId, request));
    }

    @GetMapping
    public ResponseEntity<List<BoardInvitationDto>> pending(@PathVariable Integer boardId,
                                                            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(invitationService.pendingFor(currentUser, boardId));
    }

    @DeleteMapping("/{invitationId}")
    public ResponseEntity<Void> revoke(@PathVariable Integer boardId,
                                       @PathVariable Integer invitationId,
                                       @AuthenticationPrincipal User currentUser) {
        invitationService.revoke(currentUser, boardId, invitationId);
        return ResponseEntity.noContent().build();
    }
}
