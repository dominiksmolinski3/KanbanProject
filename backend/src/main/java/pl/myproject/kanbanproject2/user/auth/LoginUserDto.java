package pl.myproject.kanbanproject2.user.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class LoginUserDto {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;

    /**
     * Verified by CaptchaVerifier when security.captcha.enabled is on, and ignored when it is not.
     * Unvalidated here deliberately: a missing token is a captcha failure rather than a field
     * error, so that both answers - absent and wrong - come out of the same place.
     */
    private CaptchaDto captcha;
}