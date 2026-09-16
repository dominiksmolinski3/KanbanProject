package pl.myproject.kanbanproject2.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The store a deployment with no Azure account gets - CI, and a fresh clone. It refuses rather than
 * pretends: unlike a dropped mail, which nobody is watching for, an upload has somebody watching a
 * progress bar, so a 503 naming the reason is the only honest answer.
 */
class DisabledBlobStoreTest {

    private final BlobStore store = new DisabledBlobStore();

    @Test
    @DisplayName("says it is not configured, which is what the upload path and health check ask")
    void reportsItselfUnconfigured() {
        assertThat(store.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("an upload is refused with the reason, not accepted and dropped")
    void refusesUploads() {
        assertThatThrownBy(() -> store.put("tasks/1/abc", "text/plain",
                new ByteArrayInputStream(new byte[1]), 1))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a download is refused for the same reason")
    void refusesReads() {
        assertThatThrownBy(() -> store.read("tasks/1/abc"))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);
    }

    @Test
    @DisplayName("a resumed download is refused too, rather than answering an empty range")
    void refusesRangedReads() {
        assertThatThrownBy(() -> store.read("tasks/1/abc", 0, 10))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);
    }

    @Test
    @DisplayName("removing is a no-op, so deleting a row left over from a configured past still works")
    void removingSucceeds() {
        assertThatCode(() -> store.remove("tasks/1/abc")).doesNotThrowAnyException();
    }
}
