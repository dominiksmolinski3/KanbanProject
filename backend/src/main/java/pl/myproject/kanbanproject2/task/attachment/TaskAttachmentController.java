package pl.myproject.kanbanproject2.task.attachment;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.exception.ErrorResponse;
import pl.myproject.kanbanproject2.user.User;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/tasks/{taskId}/attachments")
@RequiredArgsConstructor
public class TaskAttachmentController {

    private static final String BYTES = "bytes";

    private final TaskAttachmentService attachmentService;

    @GetMapping
    public ResponseEntity<List<TaskAttachmentDto>> list(@PathVariable Integer taskId,
                                                        @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(attachmentService.list(currentUser, taskId));
    }

    @PostMapping
    public ResponseEntity<TaskAttachmentDto> upload(@PathVariable Integer taskId,
                                                    @RequestParam("file") MultipartFile file,
                                                    @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attachmentService.upload(currentUser, taskId, file));
    }

    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<InputStreamResource> content(
            @PathVariable Integer taskId,
            @PathVariable Long attachmentId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            @AuthenticationPrincipal User currentUser) {
        TaskAttachmentContent attachment =
                attachmentService.content(currentUser, taskId, attachmentId, singleRangeIn(rangeHeader));

        MediaType mediaType = StringUtils.hasText(attachment.contentType())
                ? MediaType.parseMediaType(attachment.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;

        var response = ResponseEntity
                .status(attachment.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                .contentType(mediaType)
                .contentLength(attachment.rangeLength())
                .header(HttpHeaders.ACCEPT_RANGES, BYTES)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString());
        if (attachment.partial()) {
            response.header(HttpHeaders.CONTENT_RANGE, attachment.contentRange());
        }
        return response.body(new InputStreamResource(attachment.stream()));
    }

    private static HttpRange singleRangeIn(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(header);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return ranges.size() == 1 ? ranges.get(0) : null;
    }

    @ExceptionHandler(UnsatisfiableRangeException.class)
    public ResponseEntity<ErrorResponse> handleUnsatisfiableRange(UnsatisfiableRangeException ex) {
        return ResponseEntity
                .status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.ACCEPT_RANGES, BYTES)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + ex.getTotalSizeBytes())
                .body(ErrorResponse.from(ex));
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable Integer taskId,
                                       @PathVariable Long attachmentId,
                                       @AuthenticationPrincipal User currentUser) {
        attachmentService.delete(currentUser, taskId, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
