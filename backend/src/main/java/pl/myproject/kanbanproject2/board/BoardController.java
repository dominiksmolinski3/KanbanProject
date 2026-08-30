package pl.myproject.kanbanproject2.board;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

@RestController
@RequestMapping("/boards")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    @GetMapping
    public ResponseEntity<List<BoardDto>> myBoards(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(boardService.myBoards(currentUser));
    }

    /**
     * The board the other listings answer with when they are not given a {@code boardId}. The
     * client asks for it once on load rather than guessing, which is what lets every other route
     * keep the shape it had before boards existed.
     */
    @GetMapping("/current")
    public ResponseEntity<BoardDto> currentBoard(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(boardService.currentBoard(currentUser));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BoardDto> getBoard(@PathVariable Integer id,
                                             @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(boardService.getBoard(currentUser, id));
    }

    @PostMapping
    public ResponseEntity<BoardDto> createBoard(@Valid @RequestBody CreateBoardRequest request,
                                                @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(boardService.createBoard(currentUser, request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<BoardDto> renameBoard(@PathVariable Integer id,
                                                @Valid @RequestBody PatchBoardRequest request,
                                                @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(boardService.renameBoard(currentUser, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBoard(@PathVariable Integer id,
                                            @AuthenticationPrincipal User currentUser) {
        boardService.deleteBoard(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<BoardDto> addMember(@PathVariable Integer id,
                                              @Valid @RequestBody AddMemberRequest request,
                                              @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(boardService.addMember(currentUser, id, request));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<BoardDto> removeMember(@PathVariable Integer id,
                                                 @PathVariable Integer userId,
                                                 @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(boardService.removeMember(currentUser, id, userId));
    }
}
