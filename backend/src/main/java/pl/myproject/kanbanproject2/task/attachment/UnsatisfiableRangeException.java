package pl.myproject.kanbanproject2.task.attachment;

import lombok.Getter;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

/**
 * A {@code Range} header naming bytes this attachment does not have.
 *
 * <p>A {@link GlobalException} so that it renders as a 416 with this project's error body wherever
 * it is caught, and its own type so that {@code TaskAttachmentController} can catch <em>this</em>
 * one and add the header that makes a 416 worth sending. RFC 9110 asks for
 * {@code Content-Range: bytes *&#47;<total>} on the refusal, which is the only thing that tells a
 * client resuming from a stale idea of the length what the length actually is - without it the
 * client can only guess, and guessing is what produced the bad range.
 *
 * <p>The total size travels on the exception because the service is what knows it and the
 * controller is what writes headers. {@code GlobalExceptionHandler} has no per-exception header
 * plumbing, and adding some for one route was the same trade the {@code Retry-After} on
 * {@code ATTACHMENT_TRANSFER_BUSY} was declined for - a controller-local {@code @ExceptionHandler}
 * is the framework's own answer and costs nothing outside this file.
 */
@Getter
public class UnsatisfiableRangeException extends GlobalException {

    private final long totalSizeBytes;

    public UnsatisfiableRangeException(long totalSizeBytes) {
        super(ExceptionIdentifier.ATTACHMENT_RANGE_NOT_SATISFIABLE);
        this.totalSizeBytes = totalSizeBytes;
    }
}
