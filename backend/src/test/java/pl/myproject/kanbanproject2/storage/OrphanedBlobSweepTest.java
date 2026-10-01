package pl.myproject.kanbanproject2.storage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrphanedBlobSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-01T03:45:00Z");
    private static final Instant OLD = NOW.minus(OrphanedBlobSweep.GRACE).minusSeconds(1);
    private static final Instant YOUNG = NOW.minus(OrphanedBlobSweep.GRACE).plusSeconds(1);

    private BlobStore store;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        store = mock(BlobStore.class);
        when(store.isConfigured()).thenReturn(true);
        registry = new SimpleMeterRegistry();
    }

    private OrphanedBlobSweep sweep(BlobOwner... owners) {
        return new OrphanedBlobSweep(store, List.of(owners), Clock.fixed(NOW, ZoneOffset.UTC), registry);
    }

    private static BlobOwner owner(String prefix, Set<String> referenced) {
        return new BlobOwner() {
            @Override
            public String prefix() {
                return prefix;
            }

            @Override
            public Set<String> referenced(Collection<String> blobNames) {
                return Set.copyOf(blobNames.stream().filter(referenced::contains).toList());
            }
        };
    }

    @Test
    @DisplayName("removes an old blob no row names, and keeps one a row still names")
    void removesOnlyUnreferenced() {
        when(store.list("tasks/")).thenReturn(List.of(
                new BlobStore.StoredBlob("tasks/1/kept", OLD),
                new BlobStore.StoredBlob("tasks/1/orphan", OLD)));

        sweep(owner("tasks/", Set.of("tasks/1/kept"))).sweep();

        verify(store).remove("tasks/1/orphan");
        verify(store, never()).remove("tasks/1/kept");
        assertThat(registry.counter(OrphanedBlobSweep.REMOVED_COUNTER).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("leaves a blob younger than the grace period, since its upload may not have committed yet")
    void sparesYoungBlobs() {
        when(store.list("tasks/")).thenReturn(List.of(new BlobStore.StoredBlob("tasks/1/in-flight", YOUNG)));

        sweep(owner("tasks/", Set.of())).sweep();

        verify(store, never()).remove(anyString());
    }

    @Test
    @DisplayName("asks each owner about its own prefix only")
    void sweepsEveryOwner() {
        when(store.list("tasks/")).thenReturn(List.of(new BlobStore.StoredBlob("tasks/1/a", OLD)));
        when(store.list("avatars/")).thenReturn(List.of(new BlobStore.StoredBlob("avatars/2/b", OLD)));

        sweep(owner("tasks/", Set.of("tasks/1/a")), owner("avatars/", Set.of())).sweep();

        verify(store).remove("avatars/2/b");
        verify(store, never()).remove("tasks/1/a");
    }

    @Test
    @DisplayName("checks references in batches, so a large container is not one enormous IN list")
    void batchesTheLookup() {
        int total = OrphanedBlobSweep.BATCH_SIZE * 2 + 1;
        when(store.list("tasks/")).thenReturn(IntStream.range(0, total)
                .mapToObj(i -> new BlobStore.StoredBlob("tasks/1/" + i, OLD))
                .toList());
        BlobOwner owner = mock(BlobOwner.class);
        when(owner.prefix()).thenReturn("tasks/");
        when(owner.referenced(any())).thenReturn(Set.of());

        sweep(owner).sweep();

        verify(owner, times(3)).referenced(any());
        verify(store, times(total)).remove(anyString());
    }

    @Test
    @DisplayName("does nothing when no storage account is configured")
    void skipsWithoutStorage() {
        when(store.isConfigured()).thenReturn(false);

        sweep(owner("tasks/", Set.of())).sweep();

        verify(store, never()).list(anyString());
    }

    @Test
    @DisplayName("a store failure on one owner does not stop the next")
    void survivesAFailingOwner() {
        doThrow(new BlobStoreException("down", new RuntimeException())).when(store).list("tasks/");
        when(store.list("avatars/")).thenReturn(List.of(new BlobStore.StoredBlob("avatars/2/b", OLD)));

        sweep(owner("tasks/", Set.of()), owner("avatars/", Set.of())).sweep();

        verify(store).remove("avatars/2/b");
    }
}
