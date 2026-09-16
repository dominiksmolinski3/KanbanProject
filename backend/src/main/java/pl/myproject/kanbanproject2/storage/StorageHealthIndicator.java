package pl.myproject.kanbanproject2.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Says out loud when attachments are switched off - the same job {@code MailHealthIndicator} does.
 * A deployment with no storage account starts normally and then refuses every upload with a 503
 * only the uploader ever sees; this is where an operator finds out instead.
 *
 * <p>Reports {@code OUT_OF_SERVICE} rather than {@code DOWN} since nothing is broken, and like the
 * mail indicator it cannot take the deployment down - the container's probes address the
 * {@code readiness}/{@code liveness} groups, which a plain indicator doesn't join. Nothing here
 * touches the network: a health check that called Azure would turn a transient hiccup into a red
 * light on every poll.
 */
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
                            + "task attachments are refused rather than stored")
                    .build();
        }
        return Health.up().build();
    }
}
