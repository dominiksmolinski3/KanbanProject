package pl.myproject.kanbanproject2.task.attachment;

import org.springframework.stereotype.Component;

import java.util.function.Function;

/** Entity to DTO, the shape every other feature package here uses. */
@Component
public class TaskAttachmentMapper implements Function<TaskAttachment, TaskAttachmentDto> {

    @Override
    public TaskAttachmentDto apply(TaskAttachment attachment) {
        if (attachment == null) {
            return null;
        }
        var uploader = attachment.getUploadedBy();
        return new TaskAttachmentDto(
                attachment.getId(),
                attachment.getTask() != null ? attachment.getTask().getId() : null,
                attachment.getFileName(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                uploader != null ? uploader.getId() : null,
                uploader != null ? uploader.getName() : null,
                attachment.getUploadedAt());
    }
}
