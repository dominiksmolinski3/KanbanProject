package pl.myproject.kanbanproject2.user.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.config.security.AuthenticationService;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

@RequestMapping("/devices")
@RestController
@RequiredArgsConstructor
public class DeviceController {

    private final AuthenticationService authenticationService;

    @GetMapping
    public ResponseEntity<List<ActiveDeviceDto>> listDevices(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(authenticationService.listSessions(currentUser));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revokeDevice(@PathVariable Long id,
                                             @AuthenticationPrincipal User currentUser) {
        authenticationService.revokeSession(currentUser, id);
        return ResponseEntity.noContent().build();
    }
}
