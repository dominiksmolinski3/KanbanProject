package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

@RestController
@RequestMapping("/crypto")
@RequiredArgsConstructor
public class CryptoController {

    private final CryptoKeyService cryptoKeyService;

    @GetMapping("/status")
    public ResponseEntity<CryptoStatusDto> status(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(cryptoKeyService.status(currentUser));
    }

    @PostMapping("/identity")
    public ResponseEntity<IdentityKeyDto> createIdentity(@Valid @RequestBody IdentityKeyRequest request,
                                                         @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cryptoKeyService.createIdentity(currentUser, request));
    }

    @PutMapping("/identity")
    public ResponseEntity<IdentityKeyDto> replaceIdentity(@Valid @RequestBody IdentityKeyRequest request,
                                                          @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(cryptoKeyService.replaceIdentity(currentUser, request));
    }

    @GetMapping("/identity/backup")
    public ResponseEntity<IdentityBackupDto> backup(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(cryptoKeyService.backup(currentUser));
    }

    @PostMapping("/devices")
    public ResponseEntity<CryptoDeviceDto> registerDevice(@Valid @RequestBody RegisterDeviceRequest request,
                                                          @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cryptoKeyService.registerDevice(currentUser, request));
    }

    @PostMapping("/devices/{id}/key-packages")
    public ResponseEntity<KeyPackageCountDto> uploadKeyPackages(@PathVariable Long id,
                                                                @Valid @RequestBody KeyPackagesRequest request,
                                                                @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(cryptoKeyService.uploadKeyPackages(currentUser, id, request));
    }
}
