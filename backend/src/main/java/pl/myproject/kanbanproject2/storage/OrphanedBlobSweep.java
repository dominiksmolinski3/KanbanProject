package pl.myproject.kanbanproject2.storage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class OrphanedBlobSweep {

    static final String REMOVED_COUNTER = "kanban.storage.orphans.removed";

    // An upload writes its blob before its row commits, so a young blob with no row is still in flight.
    static final Duration GRACE = Duration.ofHours(24);

    static final int BATCH_SIZE = 500;

    private final BlobStore blobStore;
    private final List<BlobOwner> owners;
    private final Clock clock;
    private final Counter removed;

    @Autowired
    public OrphanedBlobSweep(BlobStore blobStore, List<BlobOwner> owners, MeterRegistry registry) {
        this(blobStore, owners, Clock.systemUTC(), registry);
    }

    OrphanedBlobSweep(BlobStore blobStore, List<BlobOwner> owners, Clock clock, MeterRegistry registry) {
        this.blobStore = blobStore;
        this.owners = owners;
        this.clock = clock;
        this.removed = Counter.builder(REMOVED_COUNTER).register(registry);
    }

    @Scheduled(cron = "0 45 3 * * *")
    public void sweep() {
        if (!blobStore.isConfigured()) {
            return;
        }
        Instant cutoff = clock.instant().minus(GRACE);
        for (BlobOwner owner : owners) {
            try {
                int count = sweep(owner, cutoff);
                if (count > 0) {
                    log.info("Removed {} orphaned blobs under {}", count, owner.prefix());
                }
            } catch (BlobStoreException e) {
                log.warn("Could not sweep orphaned blobs under {}: {}", owner.prefix(), e.getMessage());
            }
        }
    }

    private int sweep(BlobOwner owner, Instant cutoff) {
        List<String> candidates = blobStore.list(owner.prefix()).stream()
                .filter(blob -> blob.lastModified().isBefore(cutoff))
                .map(BlobStore.StoredBlob::name)
                .toList();

        int count = 0;
        for (int from = 0; from < candidates.size(); from += BATCH_SIZE) {
            List<String> batch = candidates.subList(from, Math.min(from + BATCH_SIZE, candidates.size()));
            Set<String> referenced = owner.referenced(batch);
            for (String name : batch) {
                if (!referenced.contains(name)) {
                    blobStore.remove(name);
                    removed.increment();
                    count++;
                }
            }
        }
        return count;
    }
}
