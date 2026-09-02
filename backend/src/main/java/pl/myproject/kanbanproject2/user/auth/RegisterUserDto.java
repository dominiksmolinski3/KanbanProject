package pl.myproject.kanbanproject2.user.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterUserDto {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    private String username;

    /**
     * Verified by CaptchaVerifier when security.captcha.enabled is on, and ignored when it is not.
     * Unvalidated here deliberately: a missing token is a captcha failure rather than a field
     * error, so that both answers - absent and wrong - come out of the same place.
     */
    private CaptchaDto captcha;

    /**
     * The language to mail this account in, as the client's own i18next tag.
     *
     * <p>Unvalidated here on purpose, like the captcha above it and for a different reason: an
     * unrecognised tag is a guess that missed rather than a request that is wrong, and
     * {@code SupportedLocales.normalise} answers it with English. Rejecting a signup because a
     * browser reported a language this application has no bundle for would be refusing an account
     * over a detail the person signing up never chose. Setting it deliberately, later, does
     * answer 400 - see {@code UNSUPPORTED_LOCALE}.
     */
    private String locale;
}
