package pl.myproject.kanbanproject2.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageHealthIndicatorTest {
    @Test
    @DisplayName("an unconfigured store is out of service, with the reason attached")
    void reportsAnUnconfiguredStore() {
        var blobStore = mock(BlobStore.class);
        when(blobStore.isConfigured()).thenReturn(false);

        var health = new StorageHealthIndicator(blobStore).health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails().get("reason").toString()).contains("no storage account");
    }

    @Test
    @DisplayName("a configured store is up")
    void reportsAConfiguredStore() {
        var blobStore = mock(BlobStore.class);
        when(blobStore.isConfigured()).thenReturn(true);

        assertThat(new StorageHealthIndicator(blobStore).health().getStatus()).isEqualTo(Status.UP);
    }
}
