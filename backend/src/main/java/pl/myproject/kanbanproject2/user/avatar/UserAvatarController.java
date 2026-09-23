package pl.myproject.kanbanproject2.user.avatar;

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;
import pl.myproject.kanbanproject2.user.UserService;

@RestController
@RequestMapping("/users/{userId}/avatar")
@RequiredArgsConstructor
public class UserAvatarController {

    private final AvatarService avatarService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<Void> upload(@PathVariable Integer userId,
                                       @RequestParam("file") MultipartFile file,
                                       @AuthenticationPrincipal User currentUser) {
        requireSelf(userId, currentUser);
        avatarService.upload(currentUser, file);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<InputStreamResource> get(@PathVariable Integer userId,
                                                   @AuthenticationPrincipal User currentUser) {
        userService.requireVisibleUser(currentUser, userId);
        AvatarContent content = avatarService.content(userId);

        MediaType mediaType = StringUtils.hasText(content.contentType())
                ? MediaType.parseMediaType(content.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(content.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(content.stream()));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable Integer userId,
                                       @AuthenticationPrincipal User currentUser) {
        requireSelf(userId, currentUser);
        avatarService.delete(currentUser);
        return ResponseEntity.noContent().build();
    }

    private void requireSelf(Integer id, User currentUser) {
        if (currentUser == null || !currentUser.getId().equals(id)) {
            throw new GlobalException(ExceptionIdentifier.NOT_ACCOUNT_OWNER);
        }
    }
}
