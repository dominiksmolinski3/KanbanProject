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

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final PasswordResetService passwordResetService;

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

    @GetMapping("/{id}/wip-status")
    public ResponseEntity<WipStatusDto> getWipStatus(@PathVariable Integer id,
                                                     @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(userService.getWipStatus(currentUser, id));
    }

    private void requireSelf(Integer id, User currentUser) {
        if (currentUser == null || !currentUser.getId().equals(id)) {
            throw new GlobalException(ExceptionIdentifier.NOT_ACCOUNT_OWNER);
        }
    }
}
