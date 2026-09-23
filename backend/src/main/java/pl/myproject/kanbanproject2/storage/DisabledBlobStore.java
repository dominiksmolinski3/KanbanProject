package pl.myproject.kanbanproject2.storage;

import lombok.extern.slf4j.Slf4j;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.io.InputStream;

@Slf4j
public class DisabledBlobStore implements BlobStore {

    @Override
    public void put(String blobName, String contentType, InputStream data, long length) {
        throw unavailable();
    }

    @Override
    public void remove(String blobName) {
        log.debug("No storage account is configured; nothing to remove for {}", blobName);
    }

    @Override
    public InputStream read(String blobName) {
        throw unavailable();
    }

    @Override
    public InputStream read(String blobName, long offset, long length) {
        throw unavailable();
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    private static GlobalException unavailable() {
        return new GlobalException(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);
    }
}
