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

/**
 * Attachments, addressed under the task that owns them rather than a top-level
 * {@code /attachments}, because an attachment has no visibility of its own — the task decides who
 * may see it, and an id from another board is a 404 rather than something the service has to
 * notice on the way past.
 */
@RestController
@RequestMapping("/tasks/{taskId}/attachments")
@RequiredArgsConstructor
public class TaskAttachmentController {

    /** The only range unit this route understands, named once so the two headers agree. */
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

    /**
     * The bytes, streamed from storage through this application to the caller.
     * {@link InputStreamResource} rather than a {@code byte[]} so a large download costs a buffer
     * rather than the whole file in heap, with {@code Content-Length} set from the row's
     * {@code size_bytes} rather than the stream. {@code Content-Disposition} is always
     * {@code attachment}, never {@code inline} — this is served same-origin with the board, so a
     * rendered HTML/SVG upload would run with every token in it. {@code Accept-Ranges: bytes} is
     * sent on every response so a client knows it may resume; a satisfiable {@code Range} gets
     * {@code 206} with a {@code Content-Range}, an unsatisfiable one a {@code 416} from the handler
     * below.
     */
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

    /**
     * The one range this route will serve, or {@code null} for the whole file. A header that won't
     * parse is ignored per RFC 9110, rather than a {@code 400} over a header nobody had to send. A
     * request for several ranges is also ignored, since answering it needs a
     * {@code multipart/byteranges} body nothing here asks for — every caller resumes with a single
     * open-ended range.
     */
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

    /**
     * A {@code 416}, with the header that makes it worth more to the client than a flat refusal.
     * Handled here rather than in {@code GlobalExceptionHandler} because the {@code Content-Range}
     * on this refusal names the real length — the fact the client was wrong about — and the shared
     * handler has no per-exception header plumbing.
     */
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
