package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RegisterDeviceRequest(
        @NotNull Long sessionId,
        @NotNull @Size(min = DeviceSignatures.KEY_BYTES, max = DeviceSignatures.KEY_BYTES) byte[] signatureKey,
        @NotNull @Size(min = 1, max = 128) byte[] credential,
        @NotNull @Size(min = DeviceSignatures.SIGNATURE_BYTES, max = DeviceSignatures.SIGNATURE_BYTES)
        byte[] identitySignature,
        @NotNull @Size(max = KeyPackagesRequest.MAX_UPLOAD) List<@Valid @NotNull KeyPackageUpload> keyPackages) {
}
