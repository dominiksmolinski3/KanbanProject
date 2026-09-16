package pl.myproject.kanbanproject2.task.attachment;

import java.time.Instant;

/**
 * What the panel needs to draw a row in the attachment list. No blob name and no URL: a download
 * link is minted per request and expires in minutes, so putting one in every listing would hand
 * out a live credential for attachments nobody opens.
 */
public record TaskAttachmentDto(Long id,
                                Integer taskId,
                                String fileName,
                                String contentType,
                                long sizeBytes,
                                Integer uploadedById,
                                String uploadedByName,
                                Instant uploadedAt) {
}
