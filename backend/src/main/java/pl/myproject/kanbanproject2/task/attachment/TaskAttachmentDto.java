package pl.myproject.kanbanproject2.task.attachment;

import java.time.Instant;

/**
 * What the panel needs to draw a row in the attachment list.
 *
 * <p>No blob name and no URL. The name is an implementation detail of where the bytes went, and a
 * link is deliberately a separate request: one is minted per download, expires in minutes, and
 * putting one in every listing would mean handing out a live credential for every attachment on a
 * board whether or not anybody opens it.
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
