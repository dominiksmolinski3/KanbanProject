package pl.myproject.kanbanproject2.task.attachment;

import lombok.Getter;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

@Getter
public class UnsatisfiableRangeException extends GlobalException {

    private final long totalSizeBytes;

    public UnsatisfiableRangeException(long totalSizeBytes) {
        super(ExceptionIdentifier.ATTACHMENT_RANGE_NOT_SATISFIABLE);
        this.totalSizeBytes = totalSizeBytes;
    }
}
