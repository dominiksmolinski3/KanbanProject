package pl.myproject.kanbanproject2.task.attachment;

import java.io.InputStream;

public record TaskAttachmentContent(InputStream stream,
                                    String fileName,
                                    String contentType,
                                    long sizeBytes,
                                    long rangeStart,
                                    long rangeLength,
                                    boolean partial) {

    static TaskAttachmentContent whole(InputStream stream, String fileName, String contentType,
                                       long sizeBytes) {
        return new TaskAttachmentContent(stream, fileName, contentType, sizeBytes, 0, sizeBytes, false);
    }

    static TaskAttachmentContent part(InputStream stream, String fileName, String contentType,
                                      long sizeBytes, long rangeStart, long rangeLength) {
        return new TaskAttachmentContent(stream, fileName, contentType, sizeBytes, rangeStart,
                rangeLength, true);
    }

    public String contentRange() {
        return "bytes " + rangeStart + "-" + (rangeStart + rangeLength - 1) + "/" + sizeBytes;
    }
}
