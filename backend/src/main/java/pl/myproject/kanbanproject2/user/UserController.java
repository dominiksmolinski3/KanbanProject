package pl.myproject.kanbanproject2.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.config.security.PasswordResetService;
import pl.myproject.kanbanproject2.user.auth.ChangePasswordRequest;

import java.util.List;

/**
 * Avatar routes used to live here ({@code /users/{id}/avatar}) and moved to
 * {@code pl.myproject.kanbanproject2.user.avatar.UserAvatarController} when FEAT-09 pointed them at
 * Blob Storage - the streaming response shape earned the same split
 * {@code TaskAttachmentController} has from {@code TaskController}. The route itself did not move.
 */
@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final PasswordResetService passwordResetService;

    /**
     * The people the caller shares a board with, not the whole {@code users} table. The route keeps
     * its path because the client asks it the same question it always did — "who can I assign this
     * to" — and only the answer has narrowed.
     */
    @GetMapping
    public ResponseEntity<List<UserDto>> getAllUsers(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(userService.getVisibleUsers(currentUser));
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> authenticatedUser(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(userMapper.apply(currentUser));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUserById(@PathVariable Integer id,
                                               @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(userService.getUserById(currentUser, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Integer id,
                                           @AuthenticationPrincipal User currentUser) {
        requireSelf(id, currentUser);
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserDto> patchUser(@PathVariable Integer id,
                                             @RequestBody UserDto userDto,
                                             @AuthenticationPrincipal User currentUser) {
        requireSelf(id, currentUser);
        return ResponseEntity.ok(userService.patchUser(userDto, id));
    }

    /**
     * Changes the caller's own password. Ownership is checked here like every other write on an
     * account, and the current password is checked in the service - a token proves less than a
     * password does, and this is the write that could lock the owner out.
     */
    @PatchMapping("/{id}/password")
    public ResponseEntity<Void> changePassword(@PathVariable Integer id,
                                               @Valid @RequestBody ChangePasswordRequest request,
                                               @AuthenticationPrincipal User currentUser) {
        requireSelf(id, currentUser);
        passwordResetService.changePassword(currentUser, request);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/wip-limit")
    public ResponseEntity<UserDto> updateWipLimit(@PathVariable Integer id,
                                                  @RequestBody Integer wipLimit,
                                                  @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(userService.updateWipLimit(currentUser, id, wipLimit));
    }

    /*
     * Readable by anyone who shares a board with the account, not only by the account itself: the
     * board shows how loaded each assignee is, and hiding that from their colleagues would make the
     * WIP limit unenforceable in the only place it is meant to be read.
     */
    @GetMapping("/{id}/wip-status")
    public ResponseEntity<WipStatusDto> getWipStatus(@PathVariable Integer id,
                                                     @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(userService.getWipStatus(currentUser, id));
    }

    /*
     * There is no role model yet, so "authenticated" is the only thing the filter chain proves.
     * Anything that rewrites or destroys an account has to prove ownership here instead. PATCH
     * matters most: email is the JWT subject and the UserDetailsService lookup key, so rewriting
     * someone else's was a complete, password-free account takeover.
     */
    private void requireSelf(Integer id, User currentUser) {
        if (currentUser == null || !currentUser.getId().equals(id)) {
            throw new GlobalException(ExceptionIdentifier.NOT_ACCOUNT_OWNER);
        }
    }
}
