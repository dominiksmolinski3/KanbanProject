package pl.myproject.kanbanproject2.user.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Asking for a reset code. A body rather than a query parameter, so the address does not end up in
 * an access log or a browser history the way {@code /auth/resend?email=} does.
 */
public record ForgotPasswordRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email address")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email) {
}
