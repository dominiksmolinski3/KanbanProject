package pl.myproject.kanbanproject2.board.invitation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.board.BoardDto;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

/**
 * The invitee's half: what am I being asked to join, and my answer. A second controller rather
 * than more methods on {@link BoardInvitationController}, because these routes aren't scoped by a
 * board — the caller has, by definition, no access to the board an invitation names.
 *
 * <p>Authenticated, like everything outside {@code PublicPaths}: an invitation is redeemed by
 * whoever holds the account at that address, not by whoever holds a link, so there is no token in
 * the mail and a forwarded message gives nobody anything.
 */
@RestController
@RequestMapping("/invitations")
@RequiredArgsConstructor
public class MyInvitationsController {

    private final BoardInvitationService invitationService;

    @GetMapping
    public ResponseEntity<List<BoardInvitationDto>> mine(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(invitationService.myInvitations(currentUser));
    }

    /** Answers with the board, because the caller can see it now and could not a moment ago. */
    @PostMapping("/{invitationId}/accept")
    public ResponseEntity<BoardDto> accept(@PathVariable Integer invitationId,
                                           @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(invitationService.accept(currentUser, invitationId));
    }

    @PostMapping("/{invitationId}/decline")
    public ResponseEntity<Void> decline(@PathVariable Integer invitationId,
                                        @AuthenticationPrincipal User currentUser) {
        invitationService.decline(currentUser, invitationId);
        return ResponseEntity.noContent().build();
    }
}
