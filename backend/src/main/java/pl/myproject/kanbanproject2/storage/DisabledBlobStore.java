package pl.myproject.kanbanproject2.storage;

import lombok.extern.slf4j.Slf4j;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.io.InputStream;

/**
 * What runs when no storage account is configured - the same allowance {@code DisabledEmailSender}
 * makes, but the opposite behaviour: a dropped mail is invisible to whoever caused it, while a
 * stalled upload has somebody watching a progress bar, so this refuses with
 * {@code 503 ATTACHMENT_STORAGE_UNAVAILABLE} rather than pretending - reused by
 * {@code pl.myproject.kanbanproject2.user.avatar.AvatarService} for exactly the same reason, since
 * an avatar upload is watched the same way a task attachment's is. Refusing here rather than at
 * startup is what lets a fresh clone and CI run without an Azure subscription;
 * {@code BlobStorageConfiguration}'s startup warning names the missing properties.
 */
@Slf4j
public class DisabledBlobStore implements BlobStore {

    @Override
    public void put(String blobName, String contentType, InputStream data, long length) {
        throw unavailable();
    }

    @Override
    public void remove(String blobName) {
        // Nothing was ever written, so there's nothing to remove; reachable only via a row that
        // predates the account being switched off, so deletion should still succeed.
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
