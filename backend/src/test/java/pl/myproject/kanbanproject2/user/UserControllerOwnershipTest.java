package pl.myproject.kanbanproject2.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.service.AvatarService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The filter chain ends at {@code .anyRequest().authenticated()} and {@code getAuthorities()} is
 * empty, so nothing below the controller distinguishes one caller from another. These tests pin
 * the ownership check that stands in for the authorization model the app does not have yet.
 */
class UserControllerOwnershipTest {

    private static final Integer OWNER_ID = 1;
    private static final Integer VICTIM_ID = 2;

    private UserService userService;
    private AvatarService avatarService;
    private UserController controller;
    private User caller;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        avatarService = mock(AvatarService.class);
        controller = new UserController(userService, mock(UserMapper.class), avatarService,
                mock(pl.myproject.kanbanproject2.config.security.PasswordResetService.class));

        caller = new User();
        caller.setId(OWNER_ID);
    }

    @Test
    @DisplayName("deleting another user's account is refused before the service is reached")
    void deleteRejectsOtherAccounts() {
        assertThatThrownBy(() -> controller.deleteUser(VICTIM_ID, caller))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.NOT_ACCOUNT_OWNER);

        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("rewriting another user's email is refused - it is the JWT subject")
    void patchRejectsOtherAccounts() {
        var takeover = new UserDto(VICTIM_ID, "attacker@evil.tld", "x", null);

        assertThatThrownBy(() -> controller.patchUser(VICTIM_ID, takeover, caller))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.NOT_ACCOUNT_OWNER);

        verify(userService, never()).patchUser(any(), any());
    }

    @Test
    @DisplayName("avatar upload and delete are refused on another user's account")
    void avatarMutationsRejectOtherAccounts() {
        assertThatThrownBy(() -> controller.uploadAvatar(VICTIM_ID, null, caller))
                .isInstanceOf(GlobalException.class);
        assertThatThrownBy(() -> controller.deleteAvatar(VICTIM_ID, caller))
                .isInstanceOf(GlobalException.class);

        verifyNoInteractions(avatarService);
    }

    @Test
    @DisplayName("an unauthenticated principal is refused rather than treated as the target")
    void nullPrincipalIsRejected() {
        assertThatThrownBy(() -> controller.deleteUser(OWNER_ID, null))
                .isInstanceOf(GlobalException.class)
                .extracting(e -> ((GlobalException) e).getIdentifier())
                .isEqualTo(ExceptionIdentifier.NOT_ACCOUNT_OWNER);
    }

    @Test
    @DisplayName("the caller can still delete and patch their own account")
    void ownAccountStillMutable() {
        var own = new UserDto(OWNER_ID, "owner@example.com", "Owner", 3);
        when(userService.patchUser(own, OWNER_ID)).thenReturn(own);

        controller.deleteUser(OWNER_ID, caller);
        var patched = controller.patchUser(OWNER_ID, own, caller);
        controller.deleteAvatar(OWNER_ID, caller);

        verify(userService).deleteUser(OWNER_ID);
        verify(avatarService).deleteAvatar(OWNER_ID);
        assertThat(patched.getBody()).isEqualTo(own);
    }

    @Test
    @DisplayName("avatars are served as an attachment with nosniff, whatever type is stored")
    void avatarResponseCannotRenderInline() {
        when(avatarService.getAvatar(eq(VICTIM_ID))).thenReturn(new byte[]{1, 2, 3});
        when(avatarService.getAvatarContentType(eq(VICTIM_ID))).thenReturn("image/svg+xml");

        var response = controller.getAvatar(VICTIM_ID, caller);

        assertThat(response.getHeaders().getFirst("Content-Disposition")).isEqualTo("attachment");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
