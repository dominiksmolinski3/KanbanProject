package pl.myproject.kanbanproject2.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one thing that tells an operator attachments are switched off.
 *
 * <p>{@code OUT_OF_SERVICE} rather than {@code DOWN} is the assertion that matters: nothing is
 * broken when no account is configured, and a red light for a deliberate state is a light people
 * stop reading. The other is that this reports at all - an unconfigured store is otherwise silent
 * until somebody tries to upload something.
 */
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
