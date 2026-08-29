package pl.myproject.kanbanproject2.user.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Changing a password from inside the application.
 *
 * <p>The current password is required even though the caller already holds a valid token. A token
 * lives an hour and cannot be revoked, so "holds a token" is a weaker claim than "knows the
 * password" - and this is the one operation that would let whoever holds a borrowed token lock the
 * owner out of their own account.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        String currentPassword,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String newPassword) {
}
