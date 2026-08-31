package pl.myproject.kanbanproject2.layout.column;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

@RestController
@RequestMapping("/columns")
public class ColumnController {

    private final ColumnService columnService;

    public ColumnController(ColumnService columnService) {
        this.columnService = columnService;
    }

    /**
     * {@code boardId} is optional and means "the board I work on" when it is left out, which is
     * what keeps the client that predates boards working unchanged. Every route that already names
     * an object takes the board from the object instead.
     */
    @GetMapping
    public ResponseEntity<List<ColumnDto>> getAllColumns(
            @RequestParam(required = false) Integer boardId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(columnService.getAllColumns(currentUser, boardId));
    }

    @PostMapping
    public ResponseEntity<ColumnResponseDto> addNewColumn(
            @Valid @RequestBody CreateColumnRequest request,
            @RequestParam(required = false) Integer boardId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(columnService.addNewColumn(currentUser, boardId, request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ColumnDto> patchColumn(@RequestBody ColumnDto column,
                                                 @PathVariable Integer id,
                                                 @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(columnService.patchColumn(currentUser, column, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteColumn(@PathVariable Integer id,
                                             @AuthenticationPrincipal User currentUser) {
        columnService.deleteColumn(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ColumnDto> getColumnById(@PathVariable Integer id,
                                                   @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(columnService.getColumnById(currentUser, id));
    }

    @PatchMapping("/{id}/position/{position}")
    public ResponseEntity<ColumnDto> updateColumnPosition(
            @PathVariable Integer id,
            @PathVariable Integer position,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(columnService.updateColumnPosition(currentUser, id, position));
    }

    /** The whole left-to-right order in one transaction. See {@code TaskController.reorderTasks}. */
    @PatchMapping("/positions")
    public ResponseEntity<List<ColumnDto>> reorderColumns(
            @Valid @RequestBody ReorderColumnsRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(columnService.reorderColumns(currentUser, request.orderedIds()));
    }
}