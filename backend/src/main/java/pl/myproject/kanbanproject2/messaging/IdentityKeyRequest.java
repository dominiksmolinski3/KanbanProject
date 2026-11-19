package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record IdentityKeyRequest(
        @NotNull @Size(min = DeviceSignatures.KEY_BYTES, max = DeviceSignatures.KEY_BYTES) byte[] publicKey,
        @NotNull @Size(min = 1, max = 1024) byte[] wrappedPrivate) {
}
