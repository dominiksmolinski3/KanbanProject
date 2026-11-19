package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record KeyPackagesRequest(
        @NotNull @Size(min = 1, max = MAX_UPLOAD) List<@Valid @NotNull KeyPackageUpload> keyPackages) {

    // ONE_TIME_PACKAGES in frontend/src/crypto/constants.ts, plus the last-resort package.
    public static final int MAX_UPLOAD = 101;
}
