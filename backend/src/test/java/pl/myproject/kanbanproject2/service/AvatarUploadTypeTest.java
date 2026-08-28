package pl.myproject.kanbanproject2.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.file.File;
import pl.myproject.kanbanproject2.file.FileRepository;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserRepository;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The uploaded bytes are echoed back from the app's own origin, so the type gate is a security
 * control rather than a validation nicety. {@code image/*} let SVG through, and the declared type
 * is attacker-controlled, so both halves - the allow-list and the magic bytes - are pinned here.
 */
class AvatarUploadTypeTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private FileRepository fileRepository;
    private UserRepository userRepository;
    private AvatarService avatarService;

    @BeforeEach
    void setUp() {
        fileRepository = mock(FileRepository.class);
        userRepository = mock(UserRepository.class);
        avatarService = new AvatarService(fileRepository, userRepository);

        var user = new User();
        user.setId(1);
        when(userRepository.findById(1)).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("an SVG is rejected even though it matches image/*")
    void svgIsRejected() {
        var svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        var file = new MockMultipartFile("file", "x.svg", "image/svg+xml", svg);

        assertThatThrownBy(() -> avatarService.uploadAvatar(1, file))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE);

        verifyNoInteractions(fileRepository);
    }

    @Test
    @DisplayName("an allow-listed type with mismatched bytes is rejected")
    void declaredTypeMustMatchTheBytes() {
        var svg = "<svg onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8);
        var file = new MockMultipartFile("file", "x.png", "image/png", svg);

        assertThatThrownBy(() -> avatarService.uploadAvatar(1, file))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.INVALID_AVATAR_FILE_TYPE);

        verifyNoInteractions(fileRepository);
    }

    @Test
    @DisplayName("a missing content type is rejected rather than dereferenced")
    void missingContentTypeIsRejected() {
        var file = new MockMultipartFile("file", "x.png", null, PNG_MAGIC);

        assertThatThrownBy(() -> avatarService.uploadAvatar(1, file))
                .isInstanceOf(GlobalException.class);
    }

    @Test
    @DisplayName("a real PNG is stored, with the parameterless type recorded")
    void realPngIsAccepted() {
        var file = new MockMultipartFile("file", "x.png", "image/PNG; charset=binary", PNG_MAGIC);

        assertThatCode(() -> avatarService.uploadAvatar(1, file)).doesNotThrowAnyException();

        var stored = org.mockito.ArgumentCaptor.forClass(File.class);
        verify(fileRepository).save(stored.capture());
        assertThat(stored.getValue().getType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("an empty upload is rejected before any repository lookup")
    void emptyUploadIsRejected() {
        var file = new MockMultipartFile("file", "x.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> avatarService.uploadAvatar(1, file))
                .isInstanceOf(GlobalException.class);

        verify(userRepository, org.mockito.Mockito.never()).save(any());
    }
}
