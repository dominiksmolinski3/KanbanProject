package pl.myproject.kanbanproject2.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("attachments")
public class StorageHealthIndicator implements HealthIndicator {

    private final BlobStore blobStore;

    public StorageHealthIndicator(BlobStore blobStore) {
        this.blobStore = blobStore;
    }

    @Override
    public Health health() {
        if (!blobStore.isConfigured()) {
            return Health.outOfService()
                    .withDetail("reason", "no storage account is configured; "
                            + "task attachments and avatars are refused rather than stored")
                    .build();
        }
        return Health.up().build();
    }
}
