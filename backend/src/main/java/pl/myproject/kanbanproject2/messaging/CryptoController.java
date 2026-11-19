package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

@RestController
@RequestMapping("/crypto")
@RequiredArgsConstructor
public class CryptoController {

    private final CryptoKeyService cryptoKeyService;
    private final PinService pinService;

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

    @PutMapping("/identity/backup")
    public ResponseEntity<IdentityKeyDto> setRecoveryKey(@Valid @RequestBody RecoveryWrapRequest request,
                                                         @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(cryptoKeyService.setRecoveryKey(currentUser, request));
    }

    @PostMapping("/pin/evaluate")
    public ResponseEntity<PinEvaluationDto> evaluatePin(@Valid @RequestBody PinEvaluationRequest request,
                                                        @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(pinService.evaluate(currentUser, request));
    }

    @PutMapping("/pin")
    public ResponseEntity<PinStatusDto> setPin(@Valid @RequestBody PinRequest request,
                                               @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(pinService.setPin(currentUser, request));
    }

    @PostMapping("/pin/unlock")
    public ResponseEntity<IdentityBackupDto> unlockWithPin(@Valid @RequestBody PinUnlockRequest request,
                                                           @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(pinService.unlock(currentUser, request));
    }

    @PostMapping("/devices")
    public ResponseEntity<CryptoDeviceDto> registerDevice(
            @Valid @RequestBody RegisterDeviceRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(cryptoKeyService.registerDevice(currentUser, request, userAgent));
    }

    @PostMapping("/devices/{id}/session")
    public ResponseEntity<CryptoDeviceDto> attachSession(
            @PathVariable Long id,
            @Valid @RequestBody AttachSessionRequest request,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(cryptoKeyService.attachSession(currentUser, id, request, userAgent));
    }

    @DeleteMapping("/devices/{id}")
    public ResponseEntity<Void> forgetDevice(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        cryptoKeyService.forgetDevice(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{id}/key-packages")
    public ResponseEntity<KeyPackageCountDto> uploadKeyPackages(@PathVariable Long id,
                                                                @Valid @RequestBody KeyPackagesRequest request,
                                                                @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(cryptoKeyService.uploadKeyPackages(currentUser, id, request));
    }
}
