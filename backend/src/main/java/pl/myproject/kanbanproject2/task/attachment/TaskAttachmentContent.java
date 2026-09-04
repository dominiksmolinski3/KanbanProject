package pl.myproject.kanbanproject2.task.attachment;

import java.io.InputStream;

/**
 * One attachment's bytes, still in the store, plus what the response should call them.
 *
 * <p>An open stream rather than an array: this is handed to the response and copied through, so a
 * ten-megabyte download never exists in the heap all at once. <b>The caller closes it</b> - the
 * controller does that by handing it to Spring, which closes the resource after writing it.
 *
 * <p>The name and type are the ones from Postgres, not from storage. The blob is stored under an
 * opaque name with no extension precisely so that nothing a person typed ever reaches the storage
 * account; this record is where the two halves are put back together.
 */
public record TaskAttachmentContent(InputStream stream,
                                    String fileName,
                                    String contentType,
                                    long sizeBytes) {
}
