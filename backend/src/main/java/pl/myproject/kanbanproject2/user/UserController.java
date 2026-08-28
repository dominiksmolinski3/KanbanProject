package pl.myproject.kanbanproject2.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.service.AvatarService;

import java.util.List;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserMapper userMapper;
    private final AvatarService avatarService;

    @GetMapping
    public ResponseEntity<List<UserDto>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> authenticatedUser(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(userMapper.apply(currentUser));
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUserById(@PathVariable Integer id) {
        return ResponseEntity.ok(userService.getUserById(id));
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

    @PostMapping("/{id}/avatar")
    public ResponseEntity<Void> uploadAvatar(@PathVariable Integer id,
                                             @RequestParam("file") MultipartFile file,
                                             @AuthenticationPrincipal User currentUser) {
        requireSelf(id, currentUser);
        avatarService.uploadAvatar(id, file);
        return ResponseEntity.noContent().build();
    }

    /*
     * Avatars are user-supplied bytes served from the app's own origin, which is where the
     * localStorage token lives. Even behind the upload allow-list in AvatarService the response
     * is pinned to attachment + nosniff, so a stored file can never render as a document here.
     */
    @GetMapping("/{id}/avatar")
    public ResponseEntity<byte[]> getAvatar(@PathVariable Integer id) {
        var avatarData = avatarService.getAvatar(id);
        var contentType = avatarService.getAvatarContentType(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment")
                .header("X-Content-Type-Options", "nosniff")
                .body(avatarData);
    }

    @DeleteMapping("/{id}/avatar")
    public ResponseEntity<Void> deleteAvatar(@PathVariable Integer id,
                                             @AuthenticationPrincipal User currentUser) {
        requireSelf(id, currentUser);
        avatarService.deleteAvatar(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/wip-limit")
    public ResponseEntity<UserDto> updateWipLimit(@PathVariable Integer id, @RequestBody Integer wipLimit) {
        return ResponseEntity.ok(userService.updateWipLimit(id, wipLimit));
    }

    @GetMapping("/{id}/wip-status")
    public ResponseEntity<Boolean> checkWipStatus(@PathVariable Integer id) {
        return ResponseEntity.ok(userService.checkWipStatus(id));
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
