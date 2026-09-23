package pl.myproject.kanbanproject2.user.avatar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class UserAvatarControllerOwnershipTest {
    private static final Integer OWNER_ID = 1;
    private static final Integer VICTIM_ID = 2;

    private AvatarService avatarService;
    private UserService userService;
    private UserAvatarController controller;
    private User caller;

    @BeforeEach
    void setUp() {
        avatarService = mock(AvatarService.class);
        userService = mock(UserService.class);
        controller = new UserAvatarController(avatarService, userService);

        caller = new User();
        caller.setId(OWNER_ID);
    }

    @Test
    @DisplayName("uploading to another account is refused before the service is reached")
    void uploadRejectsOtherAccounts() {
        assertThatThrownBy(() -> controller.upload(VICTIM_ID, null, caller))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.NOT_ACCOUNT_OWNER);

        verifyNoInteractions(avatarService);
    }

    @Test
    @DisplayName("deleting another account's avatar is refused before the service is reached")
    void deleteRejectsOtherAccounts() {
        assertThatThrownBy(() -> controller.delete(VICTIM_ID, caller))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.NOT_ACCOUNT_OWNER);

        verifyNoInteractions(avatarService);
    }

    @Test
    @DisplayName("an unauthenticated principal is refused rather than treated as the target")
    void nullPrincipalIsRejected() {
        assertThatThrownBy(() -> controller.delete(OWNER_ID, null))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.NOT_ACCOUNT_OWNER);
    }

    @Test
    @DisplayName("the caller can still upload and delete their own avatar")
    void ownAvatarStillMutable() {
        controller.upload(OWNER_ID, null, caller);
        controller.delete(OWNER_ID, caller);

        verify(avatarService).upload(caller, null);
        verify(avatarService).delete(caller);
    }

    @Test
    @DisplayName("reading goes through UserService's peer-visibility check, not requireSelf")
    void readingUsesVisibilityNotOwnership() {
        var content = new AvatarContent(java.io.InputStream.nullInputStream(), "image/png", 0);
        org.mockito.Mockito.when(avatarService.content(VICTIM_ID)).thenReturn(content);

        var response = controller.get(VICTIM_ID, caller);

        verify(userService).requireVisibleUser(caller, VICTIM_ID);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst("Content-Disposition")).isEqualTo("inline");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
