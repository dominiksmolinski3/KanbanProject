package pl.myproject.kanbanproject2.task.attachment;

import java.time.Instant;

public record TaskAttachmentDto(Long id,
                                Integer taskId,
                                String fileName,
                                String contentType,
                                long sizeBytes,
                                Integer uploadedById,
                                String uploadedByName,
                                Instant uploadedAt) {
}
