package pl.myproject.kanbanproject2.storage;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;

import java.io.InputStream;

/**
 * Task attachments in Azure Blob Storage.
 *
 * <p><b>Nothing here holds a file.</b> An upload is the multipart stream handed straight to the
 * service; a download is the service's own stream handed straight to the response - on a 512&nbsp;MB
 * container, the difference between a buffer per transfer and ten megabytes per concurrent one.
 *
 * <p><b>Every call is authenticated as the application, and the account has no key.</b>
 * {@code shared_access_key_enabled = false}, so the only way in is an Entra ID token from the
 * container's managed identity - no storage secret exists in Key Vault, the container template, or
 * Terraform state. A connection string is accepted too, only for local development against Azurite.
 *
 * <p><b>The blob name is opaque and stays here.</b> It is {@code tasks/<taskId>/<uuid>}, with no
 * extension and nothing a person typed; the name a person sees is a column in Postgres, put back on
 * the response by {@code TaskAttachmentController} - so a name in this container tells an attacker
 * nothing about what it holds.
 */
public class AzureBlobStore implements BlobStore {

    private final BlobContainerClient container;

    public AzureBlobStore(BlobContainerClient container) {
        this.container = container;
    }

    @Override
    public void put(String blobName, String contentType, InputStream data, long length) {
        try {
            container.getBlobClient(blobName).uploadWithResponse(
                    new BlobParallelUploadOptions(data, length)
                            .setHeaders(new BlobHttpHeaders().setContentType(contentType)),
                    null,
                    Context.NONE);
        } catch (RuntimeException e) {
            throw new BlobStoreException("could not store blob " + blobName, e);
        }
    }

    @Override
    public InputStream read(String blobName) {
        try {
            return container.getBlobClient(blobName).openInputStream();
        } catch (RuntimeException e) {
            throw new BlobStoreException("could not open blob " + blobName, e);
        }
    }

    /**
     * A ranged read, served by the storage account rather than by throwing bytes away here.
     * {@link BlobRange} becomes an HTTP {@code Range} on the SDK's request, so a resume at 90%
     * transfers ten percent of the file over the private endpoint instead of all of it.
     */
    @Override
    public InputStream read(String blobName, long offset, long length) {
        try {
            return container.getBlobClient(blobName).openInputStream(
                    new BlobInputStreamOptions().setRange(new BlobRange(offset, length)));
        } catch (RuntimeException e) {
            throw new BlobStoreException("could not open blob " + blobName, e);
        }
    }

    @Override
    public void remove(String blobName) {
        try {
            container.getBlobClient(blobName).deleteIfExists();
        } catch (RuntimeException e) {
            throw new BlobStoreException("could not remove blob " + blobName, e);
        }
    }
}
