package pl.myproject.kanbanproject2.task.attachment;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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
     */
    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable Integer taskId,
                                                       @PathVariable Long attachmentId,
                                                       @AuthenticationPrincipal User currentUser) {
        TaskAttachmentContent attachment = attachmentService.content(currentUser, taskId, attachmentId);

        MediaType mediaType = StringUtils.hasText(attachment.contentType())
                ? MediaType.parseMediaType(attachment.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(attachment.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(new InputStreamResource(attachment.stream()));
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable Integer taskId,
                                       @PathVariable Long attachmentId,
                                       @AuthenticationPrincipal User currentUser) {
        attachmentService.delete(currentUser, taskId, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
