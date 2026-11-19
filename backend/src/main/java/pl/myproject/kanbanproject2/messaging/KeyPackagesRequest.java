package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record KeyPackagesRequest(
        @NotNull @Size(min = 1, max = MAX_UPLOAD) List<@Valid @NotNull KeyPackageUpload> keyPackages) {

    public static final int MAX_UPLOAD = 100;
}
