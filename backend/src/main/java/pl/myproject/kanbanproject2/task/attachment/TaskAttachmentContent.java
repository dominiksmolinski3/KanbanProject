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
 *
 * <p><b>Two lengths, and they are not the same number.</b> {@link #sizeBytes} is the whole
 * attachment, which is what a {@code Content-Range} has to name whatever was asked for;
 * {@link #rangeLength} is what this particular response carries, which is what
 * {@code Content-Length} has to name. They differ exactly when {@link #partial} is set, and
 * conflating them is how a resumed download ends up truncated at the length of its own first
 * chunk.
 */
public record TaskAttachmentContent(InputStream stream,
                                    String fileName,
                                    String contentType,
                                    long sizeBytes,
                                    long rangeStart,
                                    long rangeLength,
                                    boolean partial) {

    /** The whole file - what a request with no usable {@code Range} header gets. */
    static TaskAttachmentContent whole(InputStream stream, String fileName, String contentType,
                                       long sizeBytes) {
        return new TaskAttachmentContent(stream, fileName, contentType, sizeBytes, 0, sizeBytes, false);
    }

    /**
     * One satisfiable range of it.
     *
     * <p>Built even when the range happens to cover the whole file, because a client that asked
     * with a {@code Range} is answered with a {@code 206} and a {@code Content-Range} it can check.
     * RFC 9110 allows either answer there; the one that says what was served is the more useful.
     */
    static TaskAttachmentContent part(InputStream stream, String fileName, String contentType,
                                      long sizeBytes, long rangeStart, long rangeLength) {
        return new TaskAttachmentContent(stream, fileName, contentType, sizeBytes, rangeStart,
                rangeLength, true);
    }

    /** {@code bytes <first>-<last>/<total>}, inclusive at both ends, as the header wants it. */
    public String contentRange() {
        return "bytes " + rangeStart + "-" + (rangeStart + rangeLength - 1) + "/" + sizeBytes;
    }
}
