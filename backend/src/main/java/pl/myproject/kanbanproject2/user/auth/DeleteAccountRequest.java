package pl.myproject.kanbanproject2.user.auth;

import jakarta.validation.constraints.NotBlank;

public record DeleteAccountRequest(
        @NotBlank(message = "Current password is required")
        String currentPassword) {
}
