package pl.myproject.kanbanproject2.storage;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.specialized.BlobInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The parts of the storage adapter nothing else can see.
 *
 * <p>Two properties are being pinned rather than left to a reading of the code. Neither direction
 * may hold a whole file - an upload takes the stream it was handed and a read returns the store's
 * own, because the storage account being closed to the internet is only affordable if putting the
 * bytes back on the request path costs a buffer rather than a copy. And the Azure client's
 * exceptions stop here: nothing above the storage package should be catching an SDK type.
 */
class AzureBlobStoreTest {

    private BlobContainerClient container;
    private BlobClient blob;
    private AzureBlobStore store;

    @BeforeEach
    void setUp() {
        container = mock(BlobContainerClient.class);
        blob = mock(BlobClient.class);
        when(container.getBlobClient(anyString())).thenReturn(blob);
        store = new AzureBlobStore(container);
    }

    @Test
    @DisplayName("an upload streams to the named blob and carries the content type")
    void uploadsWithItsType() {
        store.put("tasks/42/abc", "text/plain",
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 5);

        verify(container).getBlobClient("tasks/42/abc");
        var options = ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
        verify(blob).uploadWithResponse(options.capture(), eq(null), any());
        assertThat(options.getValue().getHeaders().getContentType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("a read hands back the store's own stream rather than a copy of the file")
    void readsWithoutBuffering() {
        BlobInputStream opened = mock(BlobInputStream.class);
        when(blob.openInputStream()).thenReturn(opened);

        InputStream stream = store.read("tasks/42/abc");

        assertThat(stream).isSameAs(opened);
        verify(container).getBlobClient("tasks/42/abc");
    }

    @Test
    @DisplayName("a removal that finds nothing is not a failure")
    void removesIdempotently() {
        store.remove("tasks/42/abc");

        verify(blob).deleteIfExists();
    }

    @Test
    @DisplayName("the SDK's exceptions stop here, wrapped in this package's own")
    void wrapsProviderFailures() {
        when(blob.uploadWithResponse(any(BlobParallelUploadOptions.class), any(), any()))
                .thenThrow(new IllegalStateException("service said no"));
        when(blob.openInputStream()).thenThrow(new IllegalStateException("service said no"));
        when(blob.deleteIfExists()).thenThrow(new IllegalStateException("service said no"));

        assertThatThrownBy(() -> store.put("tasks/42/abc", "text/plain",
                new ByteArrayInputStream(new byte[1]), 1))
                .isInstanceOf(BlobStoreException.class);
        assertThatThrownBy(() -> store.read("tasks/42/abc"))
                .isInstanceOf(BlobStoreException.class);
        assertThatThrownBy(() -> store.remove("tasks/42/abc"))
                .isInstanceOf(BlobStoreException.class);
    }

    @Test
    @DisplayName("a configured store says so")
    void isConfigured() {
        assertThat(store.isConfigured()).isTrue();
    }
}
