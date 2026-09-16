package pl.myproject.kanbanproject2.task.attachment;

import java.io.InputStream;

/**
 * One attachment's bytes, still in the store, plus what the response should call them. An open
 * stream rather than an array, so a large download never sits in the heap at once — the caller
 * closes it, which the controller does by handing it to Spring. The name and type come from
 * Postgres rather than storage, since the blob itself is opaque. {@link #sizeBytes} is the whole
 * attachment, for {@code Content-Range}; {@link #rangeLength} is what this response carries, for
 * {@code Content-Length} — conflating the two is how a resumed download ends up truncated.
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
     * One satisfiable range of it, built even when it covers the whole file, so a client that sent
     * a {@code Range} always gets back a {@code 206} with a {@code Content-Range} it can check.
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
