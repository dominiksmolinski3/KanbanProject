package pl.myproject.kanbanproject2.user.avatar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.config.BlobStorageProperties;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.storage.BlobStore;
import pl.myproject.kanbanproject2.storage.BlobStoreException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * FEAT-09: avatars through {@link BlobStore} rather than a {@code File} row's {@code @Lob}. Mirrors
 * {@code TaskAttachmentServiceTest}'s shape - a mocked store and repository, the write ordering that
 * chooses which failure is possible, and the transfer-permit cap - with the one addition this
 * feature keeps from the {@code AvatarService} it replaces: the declared {@code Content-Type} has to
 * match the bytes, not just appear on the allow-list.
 */
class AvatarServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-01T10:15:30Z");
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private UserRepository users;
    private BlobStore blobStore;
    private AvatarService service;
    private User caller;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        blobStore = mock(BlobStore.class);

        when(blobStore.isConfigured()).thenReturn(true);
        when(users.save(any(User.class))).thenAnswer(call -> call.getArgument(0));

        caller = new User();
        caller.setId(1);
        when(users.findById(1)).thenReturn(Optional.of(caller));

        service = serviceWith(properties(8, 1));
    }

    private static BlobStorageProperties properties(int maxConcurrentTransfers, int replicaCountHint) {
        return new BlobStorageProperties("https://example.blob.core.windows.net", "",
                "task-attachments", "", maxConcurrentTransfers, replicaCountHint, 500, 1_073_741_824L);
    }

    private AvatarService serviceWith(BlobStorageProperties storageProperties) {
        return new AvatarService(users, blobStore, storageProperties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MultipartFile upload(String name, String type, byte[] content) {
        return new MockMultipartFile("file", name, type, content);
    }

    private static MultipartFile png() {
        return upload("me.png", "image/png", PNG_MAGIC);
    }

    @Nested
    @DisplayName("uploading")
    class Uploading {

        @Test
        @DisplayName("streams the bytes to the store and records what it wrote")
        void storesAndRecords() {
            service.upload(caller, png());

            var name = ArgumentCaptor.forClass(String.class);
            var type = ArgumentCaptor.forClass(String.class);
            var length = ArgumentCaptor.forClass(Long.class);
            verify(blobStore).put(name.capture(), type.capture(), any(InputStream.class), length.capture());

            assertThat(name.getValue()).startsWith("avatars/1/");
            assertThat(type.getValue()).isEqualTo("image/png");
            assertThat(length.getValue()).isEqualTo((long) PNG_MAGIC.length);

            assertThat(caller.getAvatarBlobName()).isEqualTo(name.getValue());
            assertThat(caller.getAvatarContentType()).isEqualTo("image/png");
            assertThat(caller.getAvatarSizeBytes()).isEqualTo(PNG_MAGIC.length);
            assertThat(caller.getAvatarUploadedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("the blob name carries nothing anybody typed")
        void blobNameIsOpaque() {
            service.upload(caller, upload("quarterly headshot (final).png", "image/png", PNG_MAGIC));

            var name = ArgumentCaptor.forClass(String.class);
            verify(blobStore).put(name.capture(), anyString(), any(InputStream.class), anyLong());

            assertThat(name.getValue())
                    .doesNotContain("quarterly", "headshot", "final", ".png")
                    .matches("avatars/1/[0-9a-f-]{36}");
        }

        @Test
        @DisplayName("a content type with parameters is stored as the bare type")
        void normalisesContentType() {
            service.upload(caller, upload("me.png", "IMAGE/PNG; charset=binary", PNG_MAGIC));

            verify(blobStore).put(anyString(), eq("image/png"), any(InputStream.class), anyLong());
        }

        @Test
        @DisplayName("an SVG is rejected even though it is textually declared as one of the allowed types")
        void rejectsAnSvgDeclaredAsAllowed() {
            var svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                    .getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> service.upload(caller, upload("x.svg", "image/svg+xml", svg)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE);

            verify(blobStore, never()).put(anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("a declared type with mismatched bytes is rejected - the bytes decide")
        void declaredTypeMustMatchTheBytes() {
            var svg = "<svg onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> service.upload(caller, upload("x.png", "image/png", svg)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE);

            verify(blobStore, never()).put(anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("a missing content type is rejected rather than dereferenced")
        void missingContentTypeIsRejected() {
            assertThatThrownBy(() -> service.upload(caller, upload("x.png", null, PNG_MAGIC)))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE);
        }

        @Test
        @DisplayName("an empty upload is refused before any repository lookup")
        void emptyUploadIsRejected() {
            assertThatThrownBy(() -> service.upload(caller, upload("x.png", "image/png", new byte[0])))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE);

            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("a file over the limit is refused before anything is written")
        void refusesAnOversizeFile() {
            var tooBig = new MockMultipartFile("file", "big.png", "image/png", PNG_MAGIC) {
                @Override
                public long getSize() {
                    return AvatarService.MAX_AVATAR_SIZE + 1;
                }
            };

            assertThatThrownBy(() -> service.upload(caller, tooBig))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.AVATAR_FILE_TOO_LARGE);

            verify(blobStore, never()).put(anyString(), anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("with no storage account configured the upload is a 503 and not a row")
        void refusesWhenStorageIsOff() {
            when(blobStore.isConfigured()).thenReturn(false);

            assertThatThrownBy(() -> service.upload(caller, png()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ATTACHMENT_STORAGE_UNAVAILABLE);

            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("a store that refuses the bytes leaves the account untouched")
        void writesNoRowWhenTheStoreRefuses() {
            doThrow(new BlobStoreException("refused", new IllegalStateException()))
                    .when(blobStore).put(anyString(), anyString(), any(InputStream.class), anyLong());

            assertThatThrownBy(() -> service.upload(caller, png()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.FILE_UPLOAD_FAILED);

            verify(users, never()).save(any());
        }

        @Test
        @DisplayName("a body that cannot be read is a server error, not a validation failure")
        void reportsAnUnreadableBody() throws IOException {
            var broken = mock(MultipartFile.class);
            when(broken.isEmpty()).thenReturn(false);
            when(broken.getSize()).thenReturn((long) PNG_MAGIC.length);
            when(broken.getContentType()).thenReturn("image/png");
            when(broken.getBytes()).thenThrow(new IOException("the stream ended early"));

            assertThatThrownBy(() -> service.upload(caller, broken))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.FILE_UPLOAD_FAILED);
        }

        @Test
        @DisplayName("replacing an avatar removes the old blob and keeps only the new one on the row")
        void replacingRemovesTheOldBlob() {
            service.upload(caller, upload("first.png", "image/png", PNG_MAGIC));
            String firstBlobName = caller.getAvatarBlobName();

            service.upload(caller, upload("second.jpg", "image/jpeg",
                    new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}));

            assertThat(caller.getAvatarBlobName()).isNotEqualTo(firstBlobName);
            assertThat(caller.getAvatarContentType()).isEqualTo("image/jpeg");
            verify(blobStore).remove(firstBlobName);
            verify(blobStore, times(2)).put(anyString(), anyString(), any(InputStream.class), anyLong());
        }

        @Test
        @DisplayName("an unknown caller answers USER_NOT_FOUND")
        void refusesAnUnknownUser() {
            when(users.findById(404)).thenReturn(Optional.empty());
            var ghost = new User();
            ghost.setId(404);

            assertThatThrownBy(() -> service.upload(ghost, png()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("opens the stored blob and labels it with the type and size from the row")
        void opensTheBlob() {
            caller.setAvatarBlobName("avatars/1/blob-1");
            caller.setAvatarContentType("image/png");
            caller.setAvatarSizeBytes((long) PNG_MAGIC.length);
            when(blobStore.read("avatars/1/blob-1")).thenReturn(new ByteArrayInputStream(PNG_MAGIC));

            var content = service.content(1);

            verify(blobStore).read("avatars/1/blob-1");
            assertThat(content.contentType()).isEqualTo("image/png");
            assertThat(content.sizeBytes()).isEqualTo(PNG_MAGIC.length);
        }

        @Test
        @DisplayName("hands the stream back open rather than draining it here")
        void doesNotDrainTheStream() throws IOException {
            caller.setAvatarBlobName("avatars/1/blob-1");
            caller.setAvatarSizeBytes((long) PNG_MAGIC.length);
            when(blobStore.read("avatars/1/blob-1")).thenReturn(new ByteArrayInputStream(PNG_MAGIC));

            var content = service.content(1);

            assertThat(content.stream().readAllBytes()).isEqualTo(PNG_MAGIC);
        }

        @Test
        @DisplayName("no avatar set is AVATAR_NOT_FOUND, and storage is never opened")
        void refusesWhenThereIsNoAvatar() {
            assertThatThrownBy(() -> service.content(1))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.AVATAR_NOT_FOUND);

            verify(blobStore, never()).read(anyString());
        }

        @Test
        @DisplayName("a store that cannot open the blob is a server error, not an empty image")
        void reportsAStoreFailure() {
            caller.setAvatarBlobName("avatars/1/blob-1");
            when(blobStore.read(anyString()))
                    .thenThrow(new BlobStoreException("gone", new IllegalStateException()));

            assertThatThrownBy(() -> service.content(1))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.FILE_UPLOAD_FAILED);
        }
    }

    @Nested
    @DisplayName("deleting")
    class Deleting {

        @Test
        @DisplayName("clears the row and removes the blob")
        void removesBoth() {
            caller.setAvatarBlobName("avatars/1/blob-1");
            caller.setAvatarContentType("image/png");
            caller.setAvatarSizeBytes((long) PNG_MAGIC.length);
            caller.setAvatarUploadedAt(NOW);

            service.delete(caller);

            assertThat(caller.getAvatarBlobName()).isNull();
            assertThat(caller.getAvatarContentType()).isNull();
            assertThat(caller.getAvatarSizeBytes()).isNull();
            assertThat(caller.getAvatarUploadedAt()).isNull();
            verify(blobStore).remove("avatars/1/blob-1");
        }

        @Test
        @DisplayName("no avatar set is AVATAR_NOT_FOUND, and nothing is removed")
        void refusesWhenThereIsNoAvatar() {
            assertThatThrownBy(() -> service.delete(caller))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.AVATAR_NOT_FOUND);

            verify(blobStore, never()).remove(anyString());
        }

        @Test
        @DisplayName("a store that will not delete does not fail a delete that already happened")
        void survivesAFailedBlobRemoval() {
            caller.setAvatarBlobName("avatars/1/blob-1");
            doThrow(new BlobStoreException("gone", new IllegalStateException()))
                    .when(blobStore).remove(anyString());

            service.delete(caller);

            assertThat(caller.getAvatarBlobName()).isNull();
        }
    }

    @Nested
    @DisplayName("the concurrency cap")
    class ConcurrencyCap {

        @Test
        @DisplayName("a second upload is refused while the first is mid-stream")
        void refusesASecondUploadWhileOneIsStreaming() throws Exception {
            var singleSlot = serviceWith(properties(1, 1));

            CountDownLatch uploadStarted = new CountDownLatch(1);
            CountDownLatch releaseUpload = new CountDownLatch(1);
            doAnswer(invocation -> {
                uploadStarted.countDown();
                releaseUpload.await();
                return null;
            }).when(blobStore).put(anyString(), anyString(), any(InputStream.class), anyLong());

            Thread first = new Thread(() -> singleSlot.upload(caller, png()));
            first.start();
            assertThat(uploadStarted.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> singleSlot.upload(caller, png()))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.ATTACHMENT_TRANSFER_BUSY);

            releaseUpload.countDown();
            first.join(5000);

            verify(blobStore, times(1)).put(anyString(), anyString(), any(InputStream.class), anyLong());
        }

        @Test
        @DisplayName("the configured total is divided by the replica count hint")
        void dividesTheConfiguredTotalByTheReplicaCountHint() {
            var flooredAtOne = serviceWith(properties(1, 8));

            flooredAtOne.upload(caller, png());

            verify(blobStore, times(1)).put(anyString(), anyString(), any(InputStream.class), anyLong());
        }
    }
}
