package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record IdentityKeyRequest(
        @NotNull @Size(min = IdentitySignatures.KEY_BYTES, max = IdentitySignatures.KEY_BYTES) byte[] publicKey,
        @Size(min = 1, max = 1024) byte[] wrappedPrivate) {
}
