package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

@RestController
@RequestMapping("/push")
@RequiredArgsConstructor
public class PushController {

    private final PushService push;

    @GetMapping("/config")
    public ResponseEntity<PushConfigDto> config(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(push.config());
    }

    @PostMapping("/endpoints")
    public ResponseEntity<PushEndpointDto> register(@Valid @RequestBody PushEndpointRequest request,
                                                    @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(push.register(currentUser, request));
    }

    @DeleteMapping("/endpoints/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        push.remove(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
