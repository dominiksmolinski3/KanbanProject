package pl.myproject.kanbanproject2.storage;

import lombok.extern.slf4j.Slf4j;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.io.InputStream;

/**
 * What runs when no storage account is configured.
 *
 * <p>The same allowance {@code DisabledEmailSender} makes, and the opposite behaviour, because the
 * two failures are not alike. A dropped mail is invisible to the person who caused it and there is
 * nothing useful to tell them; an upload that goes nowhere is something the person is standing in
 * front of, waiting for. So this refuses rather than pretends: {@code 503 ATTACHMENT_STORAGE_
 * UNAVAILABLE}, which says the deployment is missing something rather than that the file was wrong.
 *
 * <p>Refusing here rather than at startup is what lets a fresh clone and the CI job run the whole
 * application without an Azure subscription. The startup warning in {@code BlobStorageConfiguration}
 * names the properties to set.
 */
@Slf4j
public class DisabledBlobStore implements BlobStore {

    @Override
    public void put(String blobName, String contentType, InputStream data, long length) {
        throw unavailable();
    }

    @Override
    public void remove(String blobName) {
        // Nothing was ever written, so there is nothing to remove. Reachable only through a row
        // that predates the account being switched off, which is a state worth a line rather than
        // an exception - the caller is deleting something and deleting it succeeds.
        log.debug("No storage account is configured; nothing to remove for {}", blobName);
    }

    @Override
    public InputStream read(String blobName) {
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
