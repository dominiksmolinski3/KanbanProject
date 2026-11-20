package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.constraints.NotNull;

public record DeviceLinkAnswer(@NotNull byte[] linkerPublic, @NotNull byte[] sealed) {
}
