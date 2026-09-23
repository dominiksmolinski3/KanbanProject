package pl.myproject.kanbanproject2.storage;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;

import java.io.InputStream;

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
