package pl.myproject.kanbanproject2.task.subtask;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

@RestController
@RequestMapping("/subtasks")
@RequiredArgsConstructor
public class SubTaskController {

    private final SubTaskService subTaskService;


    @GetMapping
    public ResponseEntity<List<SubTaskDto>> getAllSubTasks(
            @RequestParam(required = false) Integer boardId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(subTaskService.getAllSubTasks(currentUser, boardId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSubTask(@PathVariable Integer id,
                                              @AuthenticationPrincipal User currentUser) {
        subTaskService.deleteSubTask(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SubTaskDto> getSubTaskById(@PathVariable Integer id,
                                                     @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(subTaskService.getSubTaskById(currentUser, id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<SubTaskDto> patchSubTask(@PathVariable Integer id,
                                                   @Valid @RequestBody PatchSubTaskRequest request,
                                                   @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(subTaskService.patchSubTask(currentUser, id, request));
    }

    @PostMapping
    public ResponseEntity<SubTaskDto> createSubTask(@Valid @RequestBody CreateSubTaskRequest request,
                                                    @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(subTaskService.addSubTask(currentUser, request));
    }

    @PutMapping("/{subTaskId}/task/{taskId}")
    public ResponseEntity<SubTaskDto> assignTaskToSubTask(@PathVariable Integer subTaskId,
                                                          @PathVariable Integer taskId,
                                                          @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(subTaskService.assignTaskToSubTask(currentUser, subTaskId, taskId));
    }

    @GetMapping("/task/{taskId}")
    public ResponseEntity<List<SubTaskDto>> getSubTasksByTaskId(@PathVariable Integer taskId,
                                                                @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(subTaskService.getSubTasksByTaskId(currentUser, taskId));
    }

    @PatchMapping("/{id}/change")
    public ResponseEntity<SubTaskDto> toggleSubTaskCompletion(@PathVariable Integer id,
                                                              @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(subTaskService.toggleSubTaskCompletion(currentUser, id));
    }

    @PatchMapping("/{id}/position/{position}")
    public ResponseEntity<SubTaskDto> updateSubTaskPosition(@PathVariable Integer id,
                                                            @PathVariable Integer position,
                                                            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(subTaskService.updateSubTaskPosition(currentUser, id, position));
    }
}