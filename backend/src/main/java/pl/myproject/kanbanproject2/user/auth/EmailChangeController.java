package pl.myproject.kanbanproject2.user.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.config.security.EmailChangeService;
import pl.myproject.kanbanproject2.config.security.LoginResponse;
import pl.myproject.kanbanproject2.config.security.ratelimit.ClientIpResolver;
import pl.myproject.kanbanproject2.exception.ExceptionIdentifier;
import pl.myproject.kanbanproject2.exception.GlobalException;
import pl.myproject.kanbanproject2.user.User;

@RestController
@RequestMapping("/users/{id}/email-change")
@RequiredArgsConstructor
public class EmailChangeController {

    private final EmailChangeService emailChangeService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping
    public ResponseEntity<Void> requestChange(@PathVariable Integer id,
                                              @Valid @RequestBody EmailChangeRequest request,
                                              @AuthenticationPrincipal User currentUser) {
        requireSelf(id, currentUser);
        emailChangeService.requestChange(currentUser, request);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<LoginResponse> confirmChange(@PathVariable Integer id,
                                                       @Valid @RequestBody ConfirmEmailChangeRequest request,
                                                       @AuthenticationPrincipal User currentUser,
                                                       HttpServletRequest httpRequest) {
        requireSelf(id, currentUser);
        DeviceContext device = new DeviceContext(
                clientIpResolver.resolve(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(emailChangeService.confirmChange(currentUser, request, device));
    }

    private static void requireSelf(Integer id, User currentUser) {
        if (currentUser == null || !currentUser.getId().equals(id)) {
            throw new GlobalException(ExceptionIdentifier.NOT_ACCOUNT_OWNER);
        }
    }
}
