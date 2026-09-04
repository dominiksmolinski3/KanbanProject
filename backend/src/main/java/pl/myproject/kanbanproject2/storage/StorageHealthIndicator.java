package pl.myproject.kanbanproject2.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Says out loud when attachments are switched off.
 *
 * <p>The same job {@code MailHealthIndicator} does, for the same silence. A deployment with no
 * storage account starts normally - that is what lets CI and a fresh clone run - and then refuses
 * every upload with a 503 that only the person uploading ever sees. This is where somebody
 * operating the deployment finds out instead.
 *
 * <p>It reports {@code OUT_OF_SERVICE} rather than {@code DOWN} for an unconfigured store, because
 * nothing is broken: the store is doing exactly what an empty configuration asks of it. And, like
 * the mail indicator, <b>it cannot take the deployment down</b> - the container's three probes all
 * address the {@code readiness} and {@code liveness} groups, which a plain indicator does not join.
 * Attachments being off is a reason to tell somebody, not a reason to restart the container.
 *
 * <p>Nothing here touches the network. A health endpoint that made a storage call would turn a
 * transient Azure hiccup into a red light on a board that is otherwise working perfectly, and would
 * do it on every poll. What can be answered locally - whether this deployment was given an account
 * at all - is the question that actually goes unanswered otherwise.
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
