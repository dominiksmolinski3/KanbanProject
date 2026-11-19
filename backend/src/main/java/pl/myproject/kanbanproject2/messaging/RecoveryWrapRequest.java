package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecoveryWrapRequest(
        @NotNull @Size(min = 1, max = 1024) byte[] wrappedPrivate,
        @NotNull @Size(min = IdentitySignatures.SIGNATURE_BYTES, max = IdentitySignatures.SIGNATURE_BYTES)
        byte[] signature) {
}
