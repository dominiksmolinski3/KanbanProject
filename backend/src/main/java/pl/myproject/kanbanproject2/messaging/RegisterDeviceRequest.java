package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RegisterDeviceRequest(
        @NotNull Long sessionId,
        @NotNull @Size(min = IdentitySignatures.KEY_BYTES, max = IdentitySignatures.KEY_BYTES) byte[] signatureKey,
        @NotNull @Size(min = 1, max = 128) byte[] credential,
        @NotNull @Size(min = IdentitySignatures.SIGNATURE_BYTES, max = IdentitySignatures.SIGNATURE_BYTES)
        byte[] identitySignature,
        @NotNull @Size(max = KeyPackagesRequest.MAX_UPLOAD) List<@Valid @NotNull KeyPackageUpload> keyPackages) {
}
