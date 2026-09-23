package pl.myproject.kanbanproject2.task.flow;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

import java.time.LocalDate;

@RestController
@RequestMapping("/flow")
@RequiredArgsConstructor
public class FlowMetricsController {

    private final FlowMetricsService flowMetricsService;

    @GetMapping
    public ResponseEntity<FlowMetricsDto> metrics(
            @RequestParam(required = false) Integer boardId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer start,
            @RequestParam(required = false) Integer done,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(flowMetricsService.metrics(currentUser, boardId, from, to, start, done));
    }

    @PutMapping("/definition")
    public ResponseEntity<FlowDefinitionDto> define(
            @RequestParam(required = false) Integer boardId,
            @RequestBody FlowDefinitionRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(flowMetricsService.define(currentUser, boardId, request));
    }
}
