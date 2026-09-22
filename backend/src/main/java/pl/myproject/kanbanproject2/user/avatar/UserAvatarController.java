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

/**
 * Avatars, addressed under the account they belong to - the same shape
 * {@code TaskAttachmentController} uses for a task's own uploads. Split out of
 * {@code UserController} because the streaming response shape (an {@link InputStreamResource}, a
 * {@code Content-Length} read off the stored row, the transfer-permit dance) is a feature of its
 * own, the same argument that put task attachments in their own package rather than folding them
 * into {@code TaskController}.
 */
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

    /**
     * The bytes, streamed from storage through this application to the caller - the same reason
     * {@code TaskAttachmentController.content} does it rather than handing out a signed URL: the
     * storage account answers nobody but this application.
     *
     * <p><b>{@code Content-Disposition: inline}, unlike a task attachment's {@code attachment}.</b>
     * An attachment forces a download because it can be any file type an uploader chose, including
     * HTML or SVG, and rendering one on this origin would be same-origin with the board and every
     * token in it. An avatar cannot be either: {@link AvatarService#upload} refuses anything outside
     * a raster allow-list (PNG, JPEG, WebP, GIF) and confirms the bytes actually are that format
     * before they are ever stored, so by the time this route serves one, rendering it inline is safe
     * by construction rather than by trust in what the uploader claimed. {@code X-Content-Type-Options:
     * nosniff} stays on regardless, so the browser never second-guesses the validated type either.
     */
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

    /**
     * The same ownership rule {@code UserController} states for every account-mutating route: there
     * is no role model, so "authenticated" alone is not enough, and an avatar is uploaded or removed
     * only by the account it belongs to.
     */
    private void requireSelf(Integer id, User currentUser) {
        if (currentUser == null || !currentUser.getId().equals(id)) {
            throw new GlobalException(ExceptionIdentifier.NOT_ACCOUNT_OWNER);
        }
    }
}
