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

import java.util.UUID;

@RestController
@RequestMapping("/crypto/links")
@RequiredArgsConstructor
public class DeviceLinkController {

    private final DeviceLinkService links;

    @PostMapping
    public ResponseEntity<DeviceLinkDto> create(@Valid @RequestBody DeviceLinkRequest request,
                                                @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(links.create(currentUser, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeviceLinkDto> get(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(links.get(currentUser, id));
    }

    @PostMapping("/{id}/answer")
    public ResponseEntity<Void> answer(@PathVariable UUID id, @Valid @RequestBody DeviceLinkAnswer answer,
                                       @AuthenticationPrincipal User currentUser) {
        links.answer(currentUser, id, answer);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal User currentUser) {
        links.delete(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
