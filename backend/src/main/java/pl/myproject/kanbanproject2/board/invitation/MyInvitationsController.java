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

@RestController
@RequestMapping("/invitations")
@RequiredArgsConstructor
public class MyInvitationsController {

    private final BoardInvitationService invitationService;

    @GetMapping
    public ResponseEntity<List<BoardInvitationDto>> mine(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(invitationService.myInvitations(currentUser));
    }

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
