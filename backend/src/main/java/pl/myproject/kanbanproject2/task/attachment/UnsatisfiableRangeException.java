package pl.myproject.kanbanproject2.task.attachment;

import lombok.Getter;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

/**
 * A {@code Range} header naming bytes this attachment does not have. A {@link GlobalException} so
 * it renders as this project's 416 body, with its own type so {@code TaskAttachmentController} can
 * catch it and add the RFC 9110 {@code Content-Range: bytes *&#47;<total>} header — the only way a
 * client resuming from a stale length learns the real one. The total size travels on the exception
 * because the service knows it and the controller writes headers, through a controller-local
 * {@code @ExceptionHandler} rather than plumbing into {@code GlobalExceptionHandler}.
 */
@Getter
public class UnsatisfiableRangeException extends GlobalException {

    private final long totalSizeBytes;

    public UnsatisfiableRangeException(long totalSizeBytes) {
        super(ExceptionIdentifier.ATTACHMENT_RANGE_NOT_SATISFIABLE);
        this.totalSizeBytes = totalSizeBytes;
    }
}
