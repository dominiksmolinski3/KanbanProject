package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record KeyPackageUpload(
        @NotNull @Size(min = 1, max = 64) byte[] ref,
        @NotNull @Size(min = 1, max = 8192) byte[] data,
        boolean lastResort) {
}
