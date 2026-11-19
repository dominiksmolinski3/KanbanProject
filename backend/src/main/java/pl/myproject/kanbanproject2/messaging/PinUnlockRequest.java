package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PinUnlockRequest(
        @NotNull @Size(min = PinService.VERIFIER_BYTES, max = PinService.VERIFIER_BYTES) byte[] proof) {
}
