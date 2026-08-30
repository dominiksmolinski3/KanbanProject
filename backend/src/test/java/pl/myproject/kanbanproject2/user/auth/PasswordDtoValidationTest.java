package pl.myproject.kanbanproject2.user.auth;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two of these records are reachable without a token, so bean validation is the only thing between
 * the request body and {@link pl.myproject.kanbanproject2.config.security.PasswordResetService}.
 *
 * <p>The password bounds are the point. BCrypt silently truncates at 72 bytes, so an unbounded
 * field lets two different passwords authenticate the same account — and a reset path that
 * accepted what signup refuses would be a way around the rule rather than a second road to the
 * same place. The six-digit pattern matters for the same reason it does on verification: it is what
 * stops a caller submitting a code shaped like something else entirely.
 */
class PasswordDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    @DisplayName("a well-formed reset request passes")
    void validResetPasses() {
        assertThat(validator.validate(
                new ResetPasswordRequest("a@example.test", "123456", "correct-horse"))).isEmpty();
    }

    @Test
    @DisplayName("the reset code must be exactly six digits")
    void resetCodeShape() {
        assertThat(validator.validate(
                new ResetPasswordRequest("a@example.test", "12345", "correct-horse"))).isNotEmpty();
        assertThat(validator.validate(
                new ResetPasswordRequest("a@example.test", "1234567", "correct-horse"))).isNotEmpty();
        assertThat(validator.validate(
                new ResetPasswordRequest("a@example.test", "abcdef", "correct-horse"))).isNotEmpty();
        assertThat(validator.validate(
                new ResetPasswordRequest("a@example.test", "  ", "correct-horse"))).isNotEmpty();
    }

    @Test
    @DisplayName("a reset refuses the same passwords signup refuses, at both ends")
    void resetPasswordBoundsMatchSignup() {
        assertThat(validator.validate(
                new ResetPasswordRequest("a@example.test", "123456", "short12"))).isNotEmpty();
        assertThat(validator.validate(
                new ResetPasswordRequest("a@example.test", "123456", "x".repeat(73)))).isNotEmpty();

        assertThat(validator.validate(
                new ResetPasswordRequest("a@example.test", "123456", "x".repeat(8)))).isEmpty();
        assertThat(validator.validate(
                new ResetPasswordRequest("a@example.test", "123456", "x".repeat(72)))).isEmpty();
    }

    @Test
    @DisplayName("a reset refuses a malformed address")
    void resetRefusesABadEmail() {
        assertThat(validator.validate(
                new ResetPasswordRequest("not-an-email", "123456", "correct-horse"))).isNotEmpty();
        assertThat(validator.validate(
                new ResetPasswordRequest("  ", "123456", "correct-horse"))).isNotEmpty();
    }

    @Test
    @DisplayName("asking for a reset needs a well-formed address and nothing else")
    void forgotPasswordConstraints() {
        assertThat(validator.validate(new ForgotPasswordRequest("a@example.test"))).isEmpty();
        assertThat(validator.validate(new ForgotPasswordRequest("not-an-email"))).isNotEmpty();
        assertThat(validator.validate(new ForgotPasswordRequest("  "))).isNotEmpty();
        assertThat(validator.validate(new ForgotPasswordRequest("a".repeat(250) + "@example.test")))
                .isNotEmpty();
    }

    @Test
    @DisplayName("changing a password requires the current one, and bounds the new one the same way")
    void changePasswordConstraints() {
        assertThat(validator.validate(
                new ChangePasswordRequest("old-password", "a-new-password"))).isEmpty();
        assertThat(validator.validate(
                new ChangePasswordRequest("  ", "a-new-password"))).isNotEmpty();
        assertThat(validator.validate(
                new ChangePasswordRequest("old-password", "short12"))).isNotEmpty();
        assertThat(validator.validate(
                new ChangePasswordRequest("old-password", "x".repeat(73)))).isNotEmpty();
    }

    @Test
    @DisplayName("the current password is not bounded - it is whatever the account already has")
    void currentPasswordIsNotLengthChecked() {
        // Someone whose password predates the 8-character minimum still has to be able to replace
        // it. Bounding the field they are moving away from would lock exactly those people out.
        assertThat(validator.validate(
                new ChangePasswordRequest("x", "a-new-password"))).isEmpty();
    }
}
