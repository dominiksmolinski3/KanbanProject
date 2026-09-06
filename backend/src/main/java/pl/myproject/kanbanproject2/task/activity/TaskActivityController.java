package pl.myproject.kanbanproject2.task.activity;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

/**
 * What has happened on a board.
 *
 * <p>Its own path rather than {@code /tasks/{id}/activity}, because the feed's subject is the
 * board: a task-scoped listing would answer "what happened to this card", which is a different
 * screen and one the column-history route already half serves. {@code ?boardId=} is optional and
 * means "the caller's own board", exactly as it does on every other listing here.
 */
@RestController
@RequestMapping("/activity")
@RequiredArgsConstructor
public class TaskActivityController {

    private final TaskActivityService activityService;

    @GetMapping
    public ResponseEntity<TaskActivityResults> feed(
            @RequestParam(required = false) Integer boardId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(activityService.feed(currentUser, boardId, page, size));
    }
}
