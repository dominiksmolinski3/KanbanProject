package pl.myproject.kanbanproject2.storage;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.options.BlobParallelUploadOptions;

import java.io.InputStream;

/**
 * Task attachments in Azure Blob Storage.
 *
 * <p><b>Nothing here holds a file.</b> An upload is the multipart stream handed straight to the
 * service; a download is the service's own stream handed straight to the response. On a container
 * with 512&nbsp;MB that is the difference between a buffer per transfer and ten megabytes per
 * concurrent one, and it is the only reason putting the bytes back on the request path is
 * affordable at all.
 *
 * <p><b>Every call is authenticated as the application, and the account has no key.</b>
 * {@code shared_access_key_enabled = false}, so the only way in is an Entra ID token from the
 * container's managed identity - there is no storage secret in Key Vault, in the container
 * template, or in Terraform state. A connection string is accepted too, and is only ever local
 * development against Azurite, whose key is a published constant.
 *
 * <p><b>The blob name is opaque and stays here.</b> It is {@code tasks/<taskId>/<uuid>}, with no
 * extension and nothing a person typed; the name a person should see is a column in Postgres and
 * is put back on the response by {@code TaskAttachmentController}. So the storage account never
 * carries a name anybody chose, and a name in this container tells an attacker nothing about what
 * it holds.
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

    @Override
    public void remove(String blobName) {
        try {
            container.getBlobClient(blobName).deleteIfExists();
        } catch (RuntimeException e) {
            throw new BlobStoreException("could not remove blob " + blobName, e);
        }
    }
}
