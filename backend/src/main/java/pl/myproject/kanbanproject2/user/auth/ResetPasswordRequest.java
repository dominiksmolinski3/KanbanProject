package pl.myproject.kanbanproject2.user.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Redeeming a reset code.
 *
 * <p>The password bounds are the same ones {@link RegisterUserDto} carries, and for the same
 * reason: BCrypt silently truncates at 72 bytes, so an unbounded field would let two different
 * passwords authenticate the same account. A reset that accepted what signup refuses would be a
 * way around the rule rather than a second path to the same place.
 */
public record ResetPasswordRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email address")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Reset code is required")
        @Pattern(regexp = "\\d{6}", message = "Reset code must consist of 6 digits")
        String resetCode,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String newPassword) {
}
