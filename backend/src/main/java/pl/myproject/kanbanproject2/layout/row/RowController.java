package pl.myproject.kanbanproject2.layout.row;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

@RestController
@RequestMapping("/rows")
@RequiredArgsConstructor
public class RowController {

    private final RowService rowService;


    @GetMapping
    public ResponseEntity<List<RowDto>> getAllRows(
            @RequestParam(required = false) Integer boardId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(rowService.getAllRows(currentUser, boardId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RowDto> getRowById(@PathVariable Integer id,
                                             @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(rowService.getRowById(currentUser, id));
    }

    @PostMapping
    public ResponseEntity<RowResponseDto> createRow(
            @Valid @RequestBody CreateRowRequest request,
            @RequestParam(required = false) Integer boardId,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(rowService.createRow(currentUser, boardId, request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<RowDto> updateRow(@RequestBody RowDto row, @PathVariable Integer id,
                                            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(rowService.patchRow(currentUser, row, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRow(@PathVariable Integer id,
                                          @AuthenticationPrincipal User currentUser) {
        rowService.deleteRow(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/position/{position}")
    public ResponseEntity<RowDto> updateRowPosition(
            @PathVariable Integer id,
            @PathVariable Integer position,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(rowService.updateRowPosition(currentUser, id, position));
    }

    /** The whole top-to-bottom order in one transaction. See {@code TaskController.reorderTasks}. */
    @PatchMapping("/positions")
    public ResponseEntity<List<RowDto>> reorderRows(
            @Valid @RequestBody ReorderRowsRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(rowService.reorderRows(currentUser, request.orderedIds()));
    }
}