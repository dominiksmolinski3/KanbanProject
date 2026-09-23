package pl.myproject.kanbanproject2.storage;

import java.io.InputStream;

public interface BlobStore {
    void put(String blobName, String contentType, InputStream data, long length);

    InputStream read(String blobName);

    InputStream read(String blobName, long offset, long length);

    void remove(String blobName);

    default boolean isConfigured() {
        return true;
    }
}
