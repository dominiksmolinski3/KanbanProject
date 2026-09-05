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
 * Attachments, addressed under the task that owns them.
 *
 * <p>Nested rather than a top-level {@code /attachments}, because an attachment has no visibility
 * of its own - the task decides who may see it. Naming the task in the path means the check runs on
 * the object that carries the board, and an id from another board is a 404 rather than something
 * the service has to notice on the way past.
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
     *
     * <p>{@link InputStreamResource} rather than a {@code byte[]}: Spring copies it to the response
     * through a buffer and closes it afterwards, so a ten-megabyte download costs a buffer rather
     * than ten megabytes of heap. The length is set from the row instead of from the stream, which
     * is why {@code size_bytes} is stored - without it the response would have to be chunked and
     * the browser could not show progress.
     *
     * <p><b>{@code attachment}, never {@code inline}.</b> This is served from the application's own
     * origin, so a rendered HTML or SVG upload would be same-origin with the board and with every
     * token in it. Forcing a download is what makes it safe to store whatever type was uploaded.
     * The type is echoed back as stored, which is safe only in company with that header.
     *
     * <p><b>{@code Accept-Ranges: bytes} is on every response, including the full one</b>, because
     * that header is how a client finds out it may resume at all. A satisfiable {@code Range} is
     * answered {@code 206} with a {@code Content-Range}; an unsatisfiable one is a {@code 416} from
     * the handler below. {@code Content-Length} names what this response carries rather than what
     * the file weighs, which for a partial response are different numbers.
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
     * The one range this route will serve, or {@code null} for "send the whole thing".
     *
     * <p>Two cases deliberately answer {@code null} rather than an error. <b>A header that will not
     * parse is ignored</b>, which is what RFC 9110 asks for - a malformed {@code Range} is a client
     * that does not know what it is asking, and the whole file is a correct answer to that, where a
     * {@code 400} would break a download over a header nobody had to send. <b>A request for several
     * ranges at once is also ignored</b>, because answering it properly means a
     * {@code multipart/byteranges} body, and nothing that talks to this API asks for one: the
     * downloader in this project resumes with a single open-ended range, and so does every
     * command-line tool. Serving the whole file is the spec-legal fallback, and it is honest in a
     * way that answering only the first of several ranges would not be.
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
     * A {@code 416}, with the header that is the only reason a {@code 416} is worth more to the
     * client than a flat refusal.
     *
     * <p>Handled here rather than in {@code GlobalExceptionHandler} because the unsatisfied-range
     * {@code Content-Range} is the payload of this particular refusal - it names the real length,
     * which is exactly the fact the client was wrong about - and the shared handler has no
     * per-exception header plumbing. A controller-local handler wins over the advice, so this is
     * the framework's own answer to "one route needs one extra header" rather than plumbing
     * invented for it.
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
