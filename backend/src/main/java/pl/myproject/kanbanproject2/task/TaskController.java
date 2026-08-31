package pl.myproject.kanbanproject2.task;

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
import java.util.Set;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;


    /**
     * {@code boardId} is optional and means "the board I work on" when it is left out. The routes
     * below that name a task take its board from the task, so only the two listings and the create
     * need it at all.
     */
    @GetMapping
    public ResponseEntity<List<TaskDto>> getAllTasks(
            @RequestParam(required = false) Integer boardId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.getAllTasks(currentUser, boardId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Integer id,
                                           @AuthenticationPrincipal User currentUser) {
        taskService.deleteTask(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskDto> getTaskById(@PathVariable Integer id,
                                               @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.getTaskById(currentUser, id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<TaskDto> patchTask(@PathVariable Integer id,
                                             @Valid @RequestBody PatchTaskRequest request,
                                             @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.patchTask(currentUser, id, request));
    }

    @PostMapping
    public ResponseEntity<TaskDto> createTask(@Valid @RequestBody CreateTaskRequest request,
                                              @RequestParam(required = false) Integer boardId,
                                              @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(taskService.addTask(currentUser, boardId, request));
    }

    @PutMapping("/{taskId}/user/{userId}")
    public ResponseEntity<TaskDto> assignUserToTask(@PathVariable Integer taskId,
                                                    @PathVariable Integer userId,
                                                    @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.assignUserToTask(currentUser, taskId, userId));
    }

    @DeleteMapping("/{taskId}/user/{userId}")
    public ResponseEntity<TaskDto> removeUserFromTask(@PathVariable Integer taskId,
                                                      @PathVariable Integer userId,
                                                      @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.removeUserFromTask(currentUser, taskId, userId));
    }

    @PatchMapping("/{id}/position/{position}")
    public ResponseEntity<TaskDto> updateTaskPosition(
            @PathVariable Integer id,
            @PathVariable Integer position,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.updateTaskPosition(currentUser, id, position));
    }

    /**
     * Reorders one cell in a single call, which is what the route above should have been all along.
     *
     * <p>Dragging a card sends one PATCH per card in the cell; with a {@code @Version} on the task
     * a card somebody else moved turns one of those into a 409 and leaves the earlier ones applied.
     * This is one transaction: the whole order takes, or none of it does and the caller reloads.
     */
    @PatchMapping("/positions")
    public ResponseEntity<List<TaskDto>> reorderTasks(
            @Valid @RequestBody ReorderTasksRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.reorderTasks(currentUser, request.orderedIds()));
    }

    @PutMapping("/{taskId}/label/{label}")
    public ResponseEntity<TaskDto> addLabelToTask(@PathVariable Integer taskId,
                                                  @PathVariable String label,
                                                  @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.addLabelToTask(currentUser, taskId, label));
    }

    @DeleteMapping("/{taskId}/label/{label}")
    public ResponseEntity<TaskDto> removeLabelFromTask(@PathVariable Integer taskId,
                                                       @PathVariable String label,
                                                       @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.removeLabelFromTask(currentUser, taskId, label));
    }

    @PatchMapping("/{taskId}/labels")
    public ResponseEntity<TaskDto> updateTaskLabels(
            @PathVariable Integer taskId,
            @RequestBody Set<String> labels,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.updateTaskLabels(currentUser, taskId, labels));
    }

    @GetMapping("/get/all/labels")
    public ResponseEntity<Set<String>> getAllLabels(
            @RequestParam(required = false) Integer boardId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.getAllLabels(currentUser, boardId));
    }

    @PutMapping("/{childTaskId}/parent/{parentTaskId}")
    public ResponseEntity<TaskDto> assignParentTask(@PathVariable Integer childTaskId,
                                                    @PathVariable Integer parentTaskId,
                                                    @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.assignParentTask(currentUser, childTaskId, parentTaskId));
    }

    @DeleteMapping("/{childTaskId}/parent")
    public ResponseEntity<TaskDto> removeParentTask(@PathVariable Integer childTaskId,
                                                    @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.removeParentTask(currentUser, childTaskId));
    }

    @GetMapping("/{taskId}/children")
    public ResponseEntity<List<TaskDto>> getChildTasks(@PathVariable Integer taskId,
                                                       @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.getChildTasks(currentUser, taskId));
    }

    @GetMapping("/{taskId}/parent")
    public ResponseEntity<TaskDto> getParentTask(@PathVariable Integer taskId,
                                                 @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.getParentTask(currentUser, taskId));
    }

    @PatchMapping("/{taskId}/complete/{status}")
    public ResponseEntity<TaskDto> updateTaskCompletion(@PathVariable Integer taskId,
                                                        @PathVariable boolean status,
                                                        @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.updateTaskCompletion(currentUser, taskId, status));
    }

    @GetMapping("/{taskId}/can-complete")
    public ResponseEntity<Boolean> canTaskBeCompleted(@PathVariable Integer taskId,
                                                      @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.canTaskBeCompleted(currentUser, taskId));
    }

    @GetMapping("/daily-focus")
    public ResponseEntity<List<TaskDto>> getDailyFocusTasks(
            @RequestParam(required = false) Integer boardId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.getDailyFocusTasks(currentUser, boardId));
    }

    @PatchMapping("/{taskId}/daily-focus/{status}")
    public ResponseEntity<TaskDto> setDailyFocus(@PathVariable Integer taskId,
                                                 @PathVariable boolean status,
                                                 @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(taskService.setDailyFocus(currentUser, taskId, status));
    }

}