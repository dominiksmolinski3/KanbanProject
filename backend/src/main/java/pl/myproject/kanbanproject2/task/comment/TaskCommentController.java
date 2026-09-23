package pl.myproject.kanbanproject2.task.comment;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

@RestController
@RequestMapping("/tasks/{taskId}/comments")
@RequiredArgsConstructor
public class TaskCommentController {

    private final TaskCommentService commentService;

    @GetMapping
    public ResponseEntity<TaskCommentResults> thread(@PathVariable Integer taskId,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(commentService.thread(currentUser, taskId, page, size));
    }

    @PostMapping
    public ResponseEntity<TaskCommentDto> add(@PathVariable Integer taskId,
                                              @Valid @RequestBody TaskCommentRequest request,
                                              @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commentService.add(currentUser, taskId, request));
    }

    @PatchMapping("/{commentId}")
    public ResponseEntity<TaskCommentDto> edit(@PathVariable Integer taskId,
                                               @PathVariable Long commentId,
                                               @Valid @RequestBody TaskCommentRequest request,
                                               @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(commentService.edit(currentUser, taskId, commentId, request));
    }

    @DeleteMapping("/{commentId}")
    public ResponseEntity<Void> delete(@PathVariable Integer taskId,
                                       @PathVariable Long commentId,
                                       @AuthenticationPrincipal User currentUser) {
        commentService.delete(currentUser, taskId, commentId);
        return ResponseEntity.noContent().build();
    }
}
