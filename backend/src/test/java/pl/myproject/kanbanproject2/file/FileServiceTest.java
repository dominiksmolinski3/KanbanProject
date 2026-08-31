package pl.myproject.kanbanproject2.file;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.board.TenancyFixtures;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may read an upload, and what counts as one.
 *
 * <p>The ownership half is the part that matters. File ids are small sequential integers, so before
 * there was an owner column, {@code GET /api/files/1..n} walked every upload in the deployment and
 * {@code DELETE} destroyed them. The column exists now; these say that the check on it is on every
 * path that reaches a row, and that a row with no owner belongs to nobody rather than to everybody -
 * which is the reading that keeps the pre-migration uploads from being the same hole again.
 *
 * <p>The validation half is ordinary, and covered here because the rules are the only thing
 * standing between a multipart body and the database: an empty file, a traversal in the name, and
 * a missing content type each have to be refused before anything is written.
 */
class FileServiceTest {

    private static final byte[] CONTENT = "a small file".getBytes();

    private FileRepository repository;
    private FileService service;
    private User owner;
    private User someoneElse;

    @BeforeEach
    void setUp() {
        repository = mock(FileRepository.class);
        service = new FileService(repository);
        owner = TenancyFixtures.user(1);
        someoneElse = TenancyFixtures.user(2);

        when(repository.save(any(File.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static MultipartFile upload(String name, String type, byte[] content) {
        return new MockMultipartFile("file", name, type, content);
    }

    private File storedFile(User fileOwner) {
        var file = new File("notes.txt", "text/plain", CONTENT, fileOwner);
        file.setId(1L);
        return file;
    }

    @Nested
    @DisplayName("uploading")
    class Uploading {

        @Test
        @DisplayName("the uploader becomes the owner, and the bytes are kept as sent")
        void theUploaderOwnsTheFile() {
            File saved = service.saveFile(owner, upload("notes.txt", "text/plain", CONTENT));

            assertThat(saved.getOwner()).isSameAs(owner);
            assertThat(saved.getName()).isEqualTo("notes.txt");
            assertThat(saved.getType()).isEqualTo("text/plain");
            assertThat(saved.getData()).isEqualTo(CONTENT);
        }

        @Test
        @DisplayName("a path in the name is cleaned away rather than stored")
        void theNameIsCleaned() {
            File saved = service.saveFile(owner, upload("reports/2026/notes.txt", "text/plain", CONTENT));

            assertThat(saved.getName()).isEqualTo("reports/2026/notes.txt");
        }

        @Test
        @DisplayName("a traversal in the name is refused outright")
        void aTraversalIsRefused() {
            assertThatThrownBy(() ->
                    service.saveFile(owner, upload("../../etc/passwd", "text/plain", CONTENT)))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("an empty upload is refused")
        void anEmptyUploadIsRefused() {
            assertThatThrownBy(() -> service.saveFile(owner, upload("notes.txt", "text/plain", new byte[0])))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("no file at all is refused")
        void noFileIsRefused() {
            assertThatThrownBy(() -> service.saveFile(owner, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a nameless upload is refused")
        void anUnnamedUploadIsRefused() {
            assertThatThrownBy(() -> service.saveFile(owner, upload("", "text/plain", CONTENT)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an upload with no content type is refused")
        void anUploadWithNoTypeIsRefused() {
            assertThatThrownBy(() -> service.saveFile(owner, upload("notes.txt", null, CONTENT)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("content type");
        }

        @Test
        @DisplayName("a body that cannot be read is a 500 with the project's own identifier")
        void anUnreadableBodyIsAnUploadFailure() throws IOException {
            var broken = mock(MultipartFile.class);
            when(broken.isEmpty()).thenReturn(false);
            when(broken.getSize()).thenReturn(12L);
            when(broken.getOriginalFilename()).thenReturn("notes.txt");
            when(broken.getContentType()).thenReturn("text/plain");
            when(broken.getBytes()).thenThrow(new IOException("the stream ended early"));

            assertThatThrownBy(() -> service.saveFile(owner, broken))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.FILE_UPLOAD_FAILED);
        }
    }

    @Nested
    @DisplayName("reading and deleting")
    class Reading {

        @Test
        @DisplayName("the owner can read their own file")
        void theOwnerCanRead() {
            when(repository.findById(1L)).thenReturn(Optional.of(storedFile(owner)));

            assertThat(service.getFile(owner, 1L).getName()).isEqualTo("notes.txt");
        }

        @Test
        @DisplayName("somebody else's file answers 404, not 403")
        void anotherAccountsFileIsNotFound() {
            when(repository.findById(1L)).thenReturn(Optional.of(storedFile(owner)));

            assertThatThrownBy(() -> service.getFile(someoneElse, 1L))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    // These ids are sequential, so 403 would let a caller count the uploads that
                    // exist without being able to read one.
                    .isEqualTo(ExceptionIdentifier.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("an id that does not exist answers exactly the same way")
        void anUnknownIdAnswersTheSame() {
            when(repository.findById(2L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFile(owner, 2L))
                    .isInstanceOf(GlobalException.class)
                    .extracting(e -> ((GlobalException) e).getIdentifier())
                    .isEqualTo(ExceptionIdentifier.FILE_NOT_FOUND);
        }

        @Test
        @DisplayName("a file with no owner belongs to nobody, not to everybody")
        void anUnownedFileBelongsToNobody() {
            when(repository.findById(1L)).thenReturn(Optional.of(storedFile(null)));

            // Anything uploaded before the owner column existed, other than an avatar, which V5
            // recovers from users.avatar_id. Guessing an owner would be worse than admitting
            // there isn't one.
            assertThatThrownBy(() -> service.getFile(owner, 1L)).isInstanceOf(GlobalException.class);
        }

        @Test
        @DisplayName("the owner can delete their own file")
        void theOwnerCanDelete() {
            var file = storedFile(owner);
            when(repository.findById(1L)).thenReturn(Optional.of(file));

            service.deleteFile(owner, 1L);

            verify(repository).delete(file);
        }

        @Test
        @DisplayName("deleting somebody else's file is refused and removes nothing")
        void deletingAnothersFileIsRefused() {
            when(repository.findById(1L)).thenReturn(Optional.of(storedFile(owner)));

            assertThatThrownBy(() -> service.deleteFile(someoneElse, 1L))
                    .isInstanceOf(GlobalException.class);

            verify(repository, never()).delete(any());
        }

        @Test
        @DisplayName("ownership compares ids, not object identity")
        void ownershipComparesIds() {
            // The caller comes from the JWT filter and the owner from the persistence context, so
            // they are different instances of the same account. User inherits identity equality,
            // which is exactly the trap that listed the caller twice in GET /api/users.
            var sameAccountDifferentInstance = TenancyFixtures.user(1);

            assertThat(storedFile(owner).isOwnedBy(sameAccountDifferentInstance)).isTrue();
            assertThat(storedFile(owner).isOwnedBy(someoneElse)).isFalse();
            assertThat(storedFile(owner).isOwnedBy(null)).isFalse();
        }
    }
}
