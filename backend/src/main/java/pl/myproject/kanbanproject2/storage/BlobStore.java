package pl.myproject.kanbanproject2.storage;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

public interface BlobStore {
    void put(String blobName, String contentType, InputStream data, long length);

    InputStream read(String blobName);

    InputStream read(String blobName, long offset, long length);

    void remove(String blobName);

    List<StoredBlob> list(String prefix);

    record StoredBlob(String name, Instant lastModified) {
    }

    default boolean isConfigured() {
        return true;
    }
}
