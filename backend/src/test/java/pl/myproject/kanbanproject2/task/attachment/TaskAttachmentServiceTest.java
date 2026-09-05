package pl.myproject.kanbanproject2.task.attachment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.board.Board;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.config.BlobStorageProperties;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.storage.BlobStore;
import pl.myproject.kanbanproject2.storage.BlobStoreException;
import pl.myproject.kanbanproject2.task.Task;
import pl.myproject.kanbanproject2.task.TaskRepository;
import pl.myproject.kanbanproject2.user.User;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may attach a file, who may read one back, and what happens to the bytes when a row goes.
 *
 * <p>Three things are being pinned here, and they are the three that would be expensive to get
 * wrong.
 *
 * <p><b>The task is the only thing that grants access.</b> An attachment has no board of its own,
 * so every path has to reach one through the task - and a task on somebody else's board must answer
 * as a task that does not exist. The nested id case is the subtle one: an attachment id from
 * another board, presented under a task the caller <em>does</em> own, has to be a 404 rather than a
 * hit, or the task in the path is decoration.
 *
 * <p><b>The blob and the row are two systems and the order between them is a choice.</b> These say
 * which failure the code chose: an orphaned blob, never a row whose bytes are gone.
 *
 * <p><b>An unconfigured store refuses rather than pretends.</b> That is the state CI and a fresh
 * clone run in, and it has to be a clear 503 rather than a row pointing at nothing.
 */
class TaskAttachmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T10:15:30Z");
    private static final byte[] CONTENT = "a small attachment".getBytes();

    private TaskAttachmentRepository attachments;
    private TaskRepository tasks;
    private BlobStore blobStore;
    private TaskAttachmentService service;

    private TenancyFixtures.Tenant tenant;
    private User caller;
    private User stranger;
    private Task task;

    @BeforeEach
    void setUp() {
        attachments = mock(TaskAttachmentRepository.class);
        tasks = mock(TaskRepository.class);
        blobStore = mock(BlobStore.class);

        when(blobStore.isConfigured()).thenReturn(true);
        when(attachments.save(any(TaskAttachment.class))).thenAnswer(call -> {
            TaskAttachment saved = call.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        tenant = TenancyFixtures.tenant();
        caller = tenant.caller();
        stranger = TenancyFixtures.user(99);
        task = taskOn(tenant.board(), 42);
        when(tasks.findById(42)).thenReturn(Optional.of(task));

        service = serviceWith(properties(8, 500, 1_073_741_824L));
    }

    /** Defaults generous enough that no existing test trips the concurrency cap or the quota. */
    private static BlobStorageProperties properties(int maxConcurrentTransfers,
                                                     long maxAttachmentsPerBoard,
                                                     long maxTotalBytesPerBoard) {
        return new BlobStorageProperties("https://example.blob.core.windows.net", "",
                "task-attachments", "", maxConcurrentTransfers, maxAttachmentsPerBoard, maxTotalBytesPerBoard);
    }

    private TaskAttachmentService serviceWith(BlobStorageProperties storageProperties) {
        return new TaskAttachmentService(
                attachments,
                tasks,
                new TaskAttachmentMapper(),
                blobStore,
                storageProperties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Task taskOn(Board board, int id) {
        var task = new Task();
        task.setId(id);
        task.setBoard(board);
        return task;
    }

    private static MultipartFile upload(String name, String type, byte[] content) {
        return new MockMultipartFile("file", name, type, content);
    }

    private TaskAttachment stored(Task owner, long id) {
        var attachment = new TaskAttachment();
        attachment.setId(id);
        attachment.setTask(owner);
        attachment.setBlobName("tasks/" + owner.getId() + "/blob-" + id);
        attachment.setFileName("notes.txt");
        attachment.setContentType("text/plain");
        attachment.setSizeBytes(CONTENT.length);
        attachment.setUploadedBy(owner.getBoard().getOwner());
        attachment.setUploadedAt(NOW);
        return attachment;
    }

    @Nested
    @DisplayName("uploading")
    class Uploading {

        @Test
        @DisplayName("streams the bytes to the store and records what it wrote")
        void storesAndRecords() {
            var dto = service.upload(caller, 42, upload("notes.txt", "text/plain", CONTENT));

            var name = ArgumentCaptor.forClass(String.class);
            var type = ArgumentCaptor.forClass(String.class);
            var length = ArgumentCaptor.forClass(Long.class);
            verify(blobStore).put(name.capture(), type.capture(), any(InputStream.class), length.capture());

            assertThat(name.getValue()).startsWith("tasks/42/");
            assertThat(type.getValue()).isEqualTo("text/plain");
            assertThat(length.getValue()).isEqualTo((long) CONTENT.length);

            assertThat(dto.fileName()).isEqualTo("notes.txt");
            assertThat(dto.contentType()).isEqualTo("text/plain");
            assertThat(dto.sizeBytes()).isEqualTo(CONTENT.length);
            assertThat(dto.taskId()).isEqualTo(42);
            assertThat(dto.uploadedById()).isEqualTo(caller.getId());
            assertThat(dto.uploadedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("the blob name carries nothing anybody typed")
        void blobNameIsOpaque() {
            service.upload(caller, 42, upload("quarterly report (final).pdf", "application/pdf", CONTENT));

            var name = ArgumentCaptor.forClass(String.class);
            verify(blobStore).put(name.capture(), anyString(), any(InputStream.class), anyLong());

            assertThat(name.getValue())
                    .as("a name a person chose has no business in a URL path on a shared account")
                    .doesNotContain("quarterly", "report", "final", ".pdf")
                    .matches("tasks/42/[0-9a-f-]{36}");
        }

        @Test
        @DisplayName("a content type with parameters is stored as the bare type")
        void normalisesContentType() {
            service.upload(caller, 42, upload("notes.txt", "TEXT/Plain; charset=utf-8", CONTENT));

            verify(blobStore).put(anyString(), eq("text/plain"), any(InputStream.class), anyLong());
        }

        @Test
        @DisplayName("an upload with no declared type is stored as octet-stream rather than guessed at")
        void defaultsAnAbsentContentType() {
            service.upload(caller, 42, upload("notes", null, CONTENT));

            verify(blobStore).put(anyString(), eq("application/octet-stream"),
                    any(InputStream.class), anyLong());
        }

        @Test
        @DisplayName("an empty upload is refused before anything is written")
        void refusesAnEmptyFile() {
            assertThatThrownBy(() -> service.upload(caller, 42, upload("empty.txt", "text/plain", new byte[0])))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_ATTACHMENT);

            verify(blobStore, never()).put(anyString(), anyString(), any(), anyLong());
            verify(attachments, never()).save(any());
        }

        @Test
        @DisplayName("a file over the limit is refused before anything is written")
        void refusesAnOversizeFile() {
            var tooBig = new MockMultipartFile("file", "big.bin", "application/octet-stream",
                    new byte[0]) {
                @Override
                public long getSize() {
                    return TaskAttachmentService.MAX_ATTACHMENT_SIZE + 1;
                }

                @Override
                public boolean isEmpty() {
                    return false;
                }
            };

            assertThatThrownBy(() -> service.upload(caller, 42, tooBig))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ATTACHMENT_TOO_LARGE);

            verify(blobStore, never()).put(anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("a name with a traversal in it is refused")
        void refusesATraversal() {
            assertThatThrownBy(() -> service.upload(caller, 42,
                    upload("../../etc/passwd", "text/plain", CONTENT)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_ATTACHMENT);
        }

        @Test
        @DisplayName("a nameless upload is refused")
        void refusesAnUnnamedFile() {
            assertThatThrownBy(() -> service.upload(caller, 42, upload("", "text/plain", CONTENT)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_ATTACHMENT);
        }

        @Test
        @DisplayName("a name too long for the column is refused rather than truncated")
        void refusesAnOverlongName() {
            String name = "a".repeat(300) + ".txt";

            assertThatThrownBy(() -> service.upload(caller, 42, upload(name, "text/plain", CONTENT)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_ATTACHMENT);
        }

        @Test
        @DisplayName("with no storage account configured the upload is a 503 and not a row")
        void refusesWhenStorageIsOff() {
            when(blobStore.isConfigured()).thenReturn(false);

            assertThatThrownBy(() -> service.upload(caller, 42, upload("notes.txt", "text/plain", CONTENT)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);

            verify(attachments, never()).save(any());
        }

        @Test
        @DisplayName("a store that refuses the bytes leaves no row behind")
        void writesNoRowWhenTheStoreRefuses() {
            doThrow(new BlobStoreException("refused", new IllegalStateException()))
                    .when(blobStore).put(anyString(), anyString(), any(InputStream.class), anyLong());

            assertThatThrownBy(() -> service.upload(caller, 42, upload("notes.txt", "text/plain", CONTENT)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.FILE_UPLOAD_FAILED);

            verify(attachments, never()).save(any());
        }

        @Test
        @DisplayName("a body that cannot be read is a server error, not a validation failure")
        void reportsAnUnreadableBody() {
            var unreadable = new MockMultipartFile("file", "notes.txt", "text/plain", CONTENT) {
                @Override
                public InputStream getInputStream() throws IOException {
                    throw new IOException("connection reset");
                }
            };

            assertThatThrownBy(() -> service.upload(caller, 42, unreadable))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.FILE_UPLOAD_FAILED);
        }

        @Test
        @DisplayName("a task on somebody else's board answers as a task that does not exist")
        void refusesAStranger() {
            assertThatThrownBy(() -> service.upload(stranger, 42, upload("notes.txt", "text/plain", CONTENT)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.TASK_NOT_FOUND);

            verify(blobStore, never()).put(anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("a task that does not exist answers the same way")
        void refusesAnUnknownTask() {
            when(tasks.findById(4242)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.upload(caller, 4242, upload("notes.txt", "text/plain", CONTENT)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.TASK_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {

        @Test
        @DisplayName("hands back what is on the task, and no blob names with it")
        void listsTheTasksAttachments() {
            when(attachments.findByTaskOrderByUploadedAtAscIdAsc(task))
                    .thenReturn(List.of(stored(task, 1L), stored(task, 2L)));

            var listed = service.list(caller, 42);

            assertThat(listed).hasSize(2);
            assertThat(listed.get(0).fileName()).isEqualTo("notes.txt");
            assertThat(listed.get(0).uploadedByName()).isEqualTo(caller.getName());
        }

        @Test
        @DisplayName("a stranger gets the task's 404, not an empty list")
        void refusesAStranger() {
            assertThatThrownBy(() -> service.list(stranger, 42))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.TASK_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("downloading")
    class Downloading {

        @BeforeEach
        void storeOne() {
            when(attachments.findById(1L)).thenReturn(Optional.of(stored(task, 1L)));
            when(blobStore.read(anyString())).thenReturn(new ByteArrayInputStream(CONTENT));
        }

        @Test
        @DisplayName("opens the stored blob and labels it with the name and type from the row")
        void opensTheBlob() {
            var content = service.content(caller, 42, 1L);

            verify(blobStore).read("tasks/42/blob-1");
            assertThat(content.fileName()).isEqualTo("notes.txt");
            assertThat(content.contentType()).isEqualTo("text/plain");
            assertThat(content.sizeBytes()).isEqualTo(CONTENT.length);
        }

        @Test
        @DisplayName("hands the stream back open, because reading it here would mean holding the file")
        void doesNotDrainTheStream() throws IOException {
            var content = service.content(caller, 42, 1L);

            assertThat(content.stream().readAllBytes()).isEqualTo(CONTENT);
        }

        @Test
        @DisplayName("an attachment on another task is not reachable by naming a task the caller owns")
        void refusesAnAttachmentFromAnotherTask() {
            var elsewhere = taskOn(tenant.board(), 43);
            when(attachments.findById(1L)).thenReturn(Optional.of(stored(elsewhere, 1L)));

            assertThatThrownBy(() -> service.content(caller, 42, 1L))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ATTACHMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("an unknown attachment id is a 404")
        void refusesAnUnknownAttachment() {
            when(attachments.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.content(caller, 42, 7L))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ATTACHMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("a store that cannot open the blob is a server error, not a truncated download")
        void reportsAStoreFailure() {
            when(blobStore.read(anyString()))
                    .thenThrow(new BlobStoreException("gone", new IllegalStateException()));

            assertThatThrownBy(() -> service.content(caller, 42, 1L))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.FILE_UPLOAD_FAILED);
        }

        @Test
        @DisplayName("a stranger gets the task 404 and the blob is never opened")
        void refusesAStranger() {
            assertThatThrownBy(() -> service.content(stranger, 42, 1L))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.TASK_NOT_FOUND);

            verify(blobStore, never()).read(anyString());
        }
    }

    @Nested
    @DisplayName("deleting")
    class Deleting {

        @Test
        @DisplayName("removes the row and then the blob")
        void removesBoth() {
            var attachment = stored(task, 1L);
            when(attachments.findById(1L)).thenReturn(Optional.of(attachment));

            service.delete(caller, 42, 1L);

            verify(attachments).delete(attachment);
            verify(blobStore).remove("tasks/42/blob-1");
        }

        @Test
        @DisplayName("a store that will not delete does not fail a delete that already happened")
        void survivesAFailedBlobRemoval() {
            var attachment = stored(task, 1L);
            when(attachments.findById(1L)).thenReturn(Optional.of(attachment));
            doThrow(new BlobStoreException("gone", new IllegalStateException()))
                    .when(blobStore).remove(anyString());

            service.delete(caller, 42, 1L);

            verify(attachments).delete(attachment);
        }

        @Test
        @DisplayName("a stranger cannot delete, and nothing is removed")
        void refusesAStranger() {
            when(attachments.findById(1L)).thenReturn(Optional.of(stored(task, 1L)));

            assertThatThrownBy(() -> service.delete(stranger, 42, 1L))
                    .isInstanceOf(GlobalException.class);

            verify(attachments, never()).delete(any());
            verify(blobStore, never()).remove(anyString());
        }
    }

    @Nested
    @DisplayName("when the task itself is deleted")
    class Cascade {

        @Test
        @DisplayName("every row goes, and so does every blob")
        void removesEverything() {
            when(attachments.findByTask(task)).thenReturn(List.of(stored(task, 1L), stored(task, 2L)));

            service.deleteAllFor(task);

            verify(attachments).deleteAll(any());
            verify(blobStore).remove("tasks/42/blob-1");
            verify(blobStore).remove("tasks/42/blob-2");
        }

        @Test
        @DisplayName("a task with no attachments touches nothing")
        void doesNothingWhenThereAreNone() {
            when(attachments.findByTask(task)).thenReturn(List.of());

            service.deleteAllFor(task);

            verify(attachments, never()).deleteAll(any());
            verify(blobStore, never()).remove(anyString());
        }
    }

    @Nested
    @DisplayName("the concurrency cap")
    class ConcurrencyCap {

        @Test
        @DisplayName("a second upload is refused while the first is mid-stream, and only one blob is written")
        void refusesASecondUploadWhileOneIsStreaming() throws Exception {
            var singleSlot = serviceWith(properties(1, 500, 1_073_741_824L));

            CountDownLatch uploadStarted = new CountDownLatch(1);
            CountDownLatch releaseUpload = new CountDownLatch(1);
            doAnswer(invocation -> {
                uploadStarted.countDown();
                releaseUpload.await();
                return null;
            }).when(blobStore).put(anyString(), anyString(), any(InputStream.class), anyLong());

            Thread first = new Thread(() ->
                    singleSlot.upload(caller, 42, upload("a.txt", "text/plain", CONTENT)));
            first.start();
            assertThat(uploadStarted.await(5, TimeUnit.SECONDS))
                    .as("the first upload never reached the store")
                    .isTrue();

            assertThatThrownBy(() -> singleSlot.upload(caller, 42, upload("b.txt", "text/plain", CONTENT)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ATTACHMENT_TRANSFER_BUSY);

            releaseUpload.countDown();
            first.join(5000);

            verify(blobStore, times(1)).put(anyString(), anyString(), any(InputStream.class), anyLong());
        }

        @Test
        @DisplayName("a second upload succeeds once the first has released its permit")
        void allowsAnUploadAfterThePermitIsReleased() {
            var singleSlot = serviceWith(properties(1, 500, 1_073_741_824L));

            singleSlot.upload(caller, 42, upload("a.txt", "text/plain", CONTENT));
            singleSlot.upload(caller, 42, upload("b.txt", "text/plain", CONTENT));

            verify(blobStore, times(2)).put(anyString(), anyString(), any(InputStream.class), anyLong());
        }

        @Test
        @DisplayName("a download holds its permit until the stream is closed, not until content() returns")
        void releasesTheDownloadPermitOnlyWhenTheStreamCloses() throws IOException {
            var singleSlot = serviceWith(properties(1, 500, 1_073_741_824L));
            when(attachments.findById(1L)).thenReturn(Optional.of(stored(task, 1L)));
            when(blobStore.read(anyString()))
                    .thenReturn(new ByteArrayInputStream(CONTENT))
                    .thenReturn(new ByteArrayInputStream(CONTENT));

            var first = singleSlot.content(caller, 42, 1L);

            assertThatThrownBy(() -> singleSlot.content(caller, 42, 1L))
                    .as("content() returning is not the same as the download finishing")
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ATTACHMENT_TRANSFER_BUSY);

            first.stream().close();

            var second = singleSlot.content(caller, 42, 1L);
            assertThat(second.stream().readAllBytes())
                    .as("the permit freed by the first close() let this one through")
                    .isEqualTo(CONTENT);
        }

        @Test
        @DisplayName("a download that fails to open the blob releases its permit immediately")
        void releasesTheDownloadPermitWhenOpeningFails() throws IOException {
            var singleSlot = serviceWith(properties(1, 500, 1_073_741_824L));
            when(attachments.findById(1L)).thenReturn(Optional.of(stored(task, 1L)));
            when(blobStore.read(anyString()))
                    .thenThrow(new BlobStoreException("gone", new IllegalStateException()))
                    .thenReturn(new ByteArrayInputStream(CONTENT));

            assertThatThrownBy(() -> singleSlot.content(caller, 42, 1L))
                    .isInstanceOf(GlobalException.class);

            // Nothing was handed a stream to close, so the permit must already be free.
            var content = singleSlot.content(caller, 42, 1L);
            assertThat(content.stream().readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Nested
    @DisplayName("the board quota")
    class Quota {

        @Test
        @DisplayName("an upload is refused once the board already holds the maximum number of attachments")
        void refusesAtTheAttachmentCountLimit() {
            var capped = serviceWith(properties(8, 1, 1_073_741_824L));
            when(attachments.countByTaskBoard(tenant.board())).thenReturn(1L);

            assertThatThrownBy(() -> attemptUpload(capped))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ATTACHMENT_QUOTA_EXCEEDED);

            verify(blobStore, never()).put(anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("an upload is allowed while the board is under the attachment count limit")
        void allowsUnderTheAttachmentCountLimit() {
            var capped = serviceWith(properties(8, 2, 1_073_741_824L));
            when(attachments.countByTaskBoard(tenant.board())).thenReturn(1L);

            capped.upload(caller, 42, upload("a.txt", "text/plain", CONTENT));

            verify(blobStore).put(anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("an upload that would push the board over its byte quota is refused before the blob is written")
        void refusesOverTheByteQuota() {
            var capped = serviceWith(properties(8, 500, CONTENT.length - 1L));
            when(attachments.totalSizeBytesByTaskBoard(tenant.board())).thenReturn(0L);

            assertThatThrownBy(() -> attemptUpload(capped))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ATTACHMENT_QUOTA_EXCEEDED);

            verify(blobStore, never()).put(anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("an upload landing exactly on the byte quota is allowed")
        void allowsExactlyAtTheByteQuota() {
            var capped = serviceWith(properties(8, 500, (long) CONTENT.length));
            when(attachments.totalSizeBytesByTaskBoard(tenant.board())).thenReturn(0L);

            capped.upload(caller, 42, upload("a.txt", "text/plain", CONTENT));

            verify(blobStore).put(anyString(), anyString(), any(), anyLong());
        }

        private void attemptUpload(TaskAttachmentService capped) {
            capped.upload(caller, 42, upload("a.txt", "text/plain", CONTENT));
        }
    }

    @Test
    @DisplayName("the mapper answers null with null rather than a half-built row")
    void mapperHandlesNull() {
        assertThat(new TaskAttachmentMapper().apply(null)).isNull();
    }

    @Test
    @DisplayName("an attachment whose uploader is gone still maps")
    void mapperHandlesAMissingUploader() {
        var attachment = stored(task, 1L);
        attachment.setUploadedBy(null);

        var dto = new TaskAttachmentMapper().apply(attachment);

        assertThat(dto.uploadedById()).isNull();
        assertThat(dto.uploadedByName()).isNull();
        assertThat(dto.fileName()).isEqualTo("notes.txt");
    }

    @Test
    @DisplayName("the bytes are handed over as a stream, never read into an array first")
    void streamsTheBody() {
        var streamed = new MockMultipartFile("file", "notes.txt", "text/plain", CONTENT) {
            @Override
            public byte[] getBytes() {
                throw new AssertionError("the service read the whole body into memory");
            }

            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(CONTENT);
            }
        };

        service.upload(caller, 42, streamed);

        verify(blobStore).put(anyString(), anyString(), any(InputStream.class), eq((long) CONTENT.length));
    }
}
